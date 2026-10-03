package com.example.arruler.processing

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private class FakePc(
    private val script: MutableList<Any>,
    private val resultFile: File? = null,
    var submitError: PcLinkException? = null
) : PcLink {
    var submitted = 0
    var cancelled: String? = null
    var statusCalls = 0
    override suspend fun ping() = ServerInfo("fake", "0", "RTX")
    override suspend fun submit(zip: File, type: JobType, onProgress: (Float) -> Unit): String {
        submitError?.let { throw it }
        assertTrue(zip.exists()); submitted++; onProgress(1f); return "job1"
    }
    override suspend fun status(jobId: String): JobStatus {
        statusCalls++
        val s = script.removeAt(0)
        if (s is PcLinkException) throw s
        return s as JobStatus
    }
    override suspend fun download(jobId: String, dest: File, onProgress: (Float) -> Unit): File {
        resultFile!!.copyTo(dest, overwrite = true); return dest
    }
    override suspend fun cancel(jobId: String) { cancelled = jobId }
}

class ProcessingServiceTest {
    @get:Rule val tmp = TemporaryFolder()

    private val okResult = ResultJson(
        jobType = "scan_analyze", measures = Measures(areaM2 = Estimate.exact(12.0)),
        stats = ProcessingStats("pc", 100)
    )

    private fun resultZip(): File = tmp.newFile().also { ResultPackage.write(it, okResult) }

    private fun job(points: Int = 1000, type: JobType = JobType.SCAN_ANALYZE, q: ObjectQuality? = null) = ProcessingJob(
        type = type, quality = q, estimate = JobEstimate(points),
        cloud = CloudData.fromXyz(FloatArray(points * 3)), workDir = tmp.newFolder()
    )

    private fun sig(pc: Boolean = true, tier: Tier = Tier.MID, pref: UserPref = UserPref.AUTO, wifi: Boolean = true) =
        RouteSignals(tier, pc, pref, wifi, false, 0)

    private val packager = JobPackager { j, dest -> dest.writeBytes(byteArrayOf(1)) }
    private val phoneOk = PhoneRunner { okResult.copy(stats = ProcessingStats("phone", 5)) }

    private fun service(s: RouteSignals, pc: PcLink?, delays: MutableList<Long> = ArrayList(), phone: PhoneRunner = phoneOk) =
        ProcessingService({ s }, pc, packager, phone, { delays += it })

    @Test fun smallJobRunsOnPhoneWithoutTouchingPc() = runBlocking {
        val pc = FakePc(mutableListOf())
        val states = service(sig(), pc).process(job()).toList()
        assertTrue(states.first() is ProcessingState.Routed)
        val done = states.last() as ProcessingState.Done
        assertEquals(Backend.PHONE, done.backend); assertEquals(0, pc.submitted)
    }

    @Test fun bigJobGoesToPcAndPollsWithBackoff() = runBlocking {
        val delays = ArrayList<Long>()
        val pc = FakePc(mutableListOf(
            JobStatus.Queued(1), JobStatus.Queued(1), JobStatus.Queued(1), JobStatus.Queued(1), JobStatus.Queued(1),
            JobStatus.Running(0.3f, "mesh"), JobStatus.Running(0.3f, "mesh"), JobStatus.Done
        ), resultZip())
        val states = service(sig(), pc, delays).process(job(points = 200_000)).toList()
        val done = states.last() as ProcessingState.Done
        assertEquals(Backend.PC, done.backend); assertEquals(12.0, done.result.measures.areaM2!!.recommended, 0.0)
        assertTrue(states.any { it is ProcessingState.Uploading })
        assertTrue(states.any { it is ProcessingState.Downloading })
        assertTrue(states.any { it is ProcessingState.Running && it.stage == "mesh" })
        // 2000 start, x1.5 per unchanged poll capped at 10000, reset to 2000 when the status changes
        assertEquals(listOf(2000L, 3000L, 4500L, 6750L, 10000L, 2000L, 3000L), delays)
    }

    @Test fun failedJobReportsMessage() = runBlocking {
        val pc = FakePc(mutableListOf(JobStatus.Failed("out of memory")))
        val last = service(sig(), pc).process(job(200_000)).toList().last()
        assertEquals("PC: out of memory", (last as ProcessingState.Failed).message)
    }

    @Test fun transientNetworkErrorsAreTolerated() = runBlocking {
        val net = PcLinkException(0, "network", "timeout", network = true)
        val pc = FakePc(mutableListOf(net, net, JobStatus.Done), resultZip())
        assertTrue(service(sig(), pc).process(job(200_000)).toList().last() is ProcessingState.Done)
    }

    @Test fun tooManyNetworkErrorsFail() = runBlocking {
        val net = PcLinkException(0, "network", "timeout", network = true)
        val pc = FakePc(MutableList(10) { net })
        assertTrue(service(sig(), pc).process(job(200_000)).toList().last() is ProcessingState.Failed)
        assertEquals(ProcessingService.MAX_POLL_ERRORS + 1, pc.statusCalls)
    }

    @Test fun authErrorFailsImmediately() = runBlocking {
        val pc = FakePc(mutableListOf(PcLinkException(401, "unauthorized", "bad token")))
        val last = service(sig(), pc).process(job(200_000)).toList().last()
        assertTrue((last as ProcessingState.Failed).message.contains("bad token"))
    }

    @Test fun unreachablePcFallsBackToPhoneForScans() = runBlocking {
        val pc = FakePc(mutableListOf(), submitError = PcLinkException(0, "network", "no route", network = true))
        val states = service(sig(), pc).process(job(200_000)).toList()
        assertTrue(states.any { it is ProcessingState.Warning })
        assertEquals(Backend.PHONE, (states.last() as ProcessingState.Done).backend)
    }

    @Test fun mobileDataBigUploadWaitsForConfirmation() = runBlocking {
        val pc = FakePc(mutableListOf(JobStatus.Done), resultZip())
        val svc = service(sig(wifi = false, tier = Tier.HIGH), pc)
        val big = job(points = 2_000_000)
        val first = svc.process(big).toList()
        assertTrue(first.last() is ProcessingState.NeedsConfirmation); assertEquals(0, pc.submitted)
        val second = svc.process(big, confirmedMobileUpload = true).toList()
        assertTrue(second.last() is ProcessingState.Done); assertEquals(1, pc.submitted)
    }

    @Test fun photogrammetryWithoutPcFails() = runBlocking {
        val j = ProcessingJob(JobType.PHOTOGRAMMETRY, ObjectQuality.DETAILED, JobEstimate(imageCount = 60), workDir = tmp.newFolder())
        val states = service(sig(pc = false), null).process(j).toList()
        assertTrue((states.first() as ProcessingState.Routed).decision.blocked)
        assertTrue(states.last() is ProcessingState.Failed)
    }

    @Test fun phoneErrorBecomesFailed() = runBlocking {
        val last = service(sig(pc = false), null, phone = { throw IllegalStateException("no room") }).process(job()).toList().last()
        assertEquals("no room", (last as ProcessingState.Failed).message)
    }

    @Test fun cancellingTheCollectorCancelsTheRemoteJob() = runBlocking {
        val script = MutableList<Any>(100) { JobStatus.Running(0.1f, "x") }
        val pc = FakePc(script)
        var n = 0
        val svc = ProcessingService({ sig() }, pc, packager, phoneOk, { n++ })
        // take states until the first Running, then stop collecting -> flow is cancelled
        svc.process(job(200_000)).first { it is ProcessingState.Running }
        assertEquals("job1", pc.cancelled)
    }

    @Test fun phoneRunnerOnSyntheticRoomIsReachable() = runBlocking {
        // Sanity: DefaultPhoneRunner rejects an empty cloud with a readable failure, not a crash.
        val j = ProcessingJob(JobType.SCAN_ANALYZE, null, JobEstimate(3), CloudData.fromXyz(FloatArray(9)), workDir = tmp.newFolder())
        val last = ProcessingService({ sig(pc = false) }, null, packager).process(j).toList().last()
        assertTrue(last is ProcessingState.Failed)
        assertFalse((last as ProcessingState.Failed).message.isEmpty())
    }
}
