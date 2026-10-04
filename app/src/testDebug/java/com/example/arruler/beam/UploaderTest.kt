package com.example.arruler.beam

import com.example.arruler.devlink.Sha256
import com.example.arruler.processing.PairingInfo
import com.example.arruler.processing.ProcJson
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** In-memory copy of the server contract (pc-server bundles.py): chunk store, resume list, sha256 check on complete. */
class FakeBundleServer(val chunkSize: Int = 1000) {
    class Slot(val name: String, val size: Long, val sha: String) { val chunks = HashMap<Int, ByteArray>(); var complete = false }
    val slots = LinkedHashMap<String, Slot>()
    val putLog = ArrayList<Pair<String, Int>>()
    var failPutsAfter = Int.MAX_VALUE          // network drop after this many successful PUTs
    var failCompleteOnce = 0                   // answer 422 this many times (and forget the slot)
    var sabotage = false                       // corrupt a stored chunk so sha256 fails
    var createCount = 0
    private var next = 0

    private fun reply(s: Slot, id: String): BundleReply =
        BundleReply(id, chunkSize, ChunkMath.count(s.size, chunkSize), s.chunks.keys.sorted(), if (s.complete) "complete" else "uploading")

    fun create(name: String, size: Long, sha: String): BundleReply {
        createCount++
        val ex = slots.entries.firstOrNull { it.value.sha == sha && it.value.size == size && !it.value.complete }
        if (ex != null) return reply(ex.value, ex.key)
        val id = "b${next++}"
        slots[id] = Slot(name, size, sha)
        return reply(slots[id]!!, id)
    }

    fun put(id: String, n: Int, bytes: ByteArray) {
        val s = slots[id] ?: throw BeamHttpException(404, "no such bundle")
        if (putLog.size >= failPutsAfter) throw BeamHttpException(0, "connection reset")
        val want = ChunkMath.length(s.size, chunkSize, n)
        if (bytes.size != want) throw BeamHttpException(400, "chunk $n is ${bytes.size} bytes, expected $want")
        s.chunks[n] = if (sabotage && n == 0) bytes.copyOf().also { it[0] = (it[0] + 1).toByte() } else bytes
        putLog += id to n
    }

    fun status(id: String) = reply(slots[id] ?: throw BeamHttpException(404, "no such bundle"), id)

    fun complete(id: String): BundleReply {
        val s = slots[id] ?: throw BeamHttpException(404, "no such bundle")
        val total = ChunkMath.count(s.size, chunkSize)
        if ((0 until total).any { it !in s.chunks }) throw BeamHttpException(409, "chunks missing")
        val md = MessageDigest.getInstance("SHA-256")
        for (n in 0 until total) md.update(s.chunks[n]!!)
        val sha = md.digest().joinToString("") { "%02x".format(it) }
        if (failCompleteOnce > 0 || sha != s.sha) {
            if (failCompleteOnce > 0) failCompleteOnce--
            slots.remove(id)
            sabotage = false
            throw BeamHttpException(422, "sha256 mismatch")
        }
        s.complete = true
        return reply(s, id)
    }
}

class FakeTransport(private val s: FakeBundleServer) : BeamTransport {
    override fun create(name: String, size: Long, sha256: String) = s.create(name, size, sha256)
    override fun putChunk(id: String, n: Int, bytes: ByteArray) = s.put(id, n, bytes)
    override fun status(id: String) = s.status(id)
    override fun complete(id: String) = s.complete(id)
}

class UploaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(size: Int): File = tmp.newFile().also { f -> f.writeBytes(ByteArray(size) { (it * 31 + 7).toByte() }) }
    private fun state(f: File) = UploadState(f.name, f.length(), Sha256.hex(f))
    private fun up(s: FakeBundleServer) = ChunkedUploader(FakeTransport(s), sleep = {})

    @Test fun chunkMath() {
        assertEquals(0, ChunkMath.count(0, 1000))
        assertEquals(3, ChunkMath.count(2500, 1000))
        assertEquals(3, ChunkMath.count(3000, 1000))
        assertEquals(500, ChunkMath.length(2500, 1000, 2))
        assertEquals(1000, ChunkMath.length(2500, 1000, 1))
    }

    @Test fun uploadsAllChunksAndCompletes() {
        val f = file(2500)
        val srv = FakeBundleServer()
        val progress = ArrayList<Float>()
        val r = up(srv).upload(f, state(f), {}, { progress += it })
        assertTrue(r is UploadResult.Done)
        assertEquals(listOf(0, 1, 2), srv.putLog.map { it.second })
        assertTrue(srv.slots.values.single().complete)
        assertEquals(1f, progress.last(), 0f)
    }

    @Test fun resumesAfterADropAndSendsOnlyTheMissingChunks() {
        val f = file(5200)
        val srv = FakeBundleServer().also { it.failPutsAfter = 2 }
        val states = ArrayList<UploadState>()
        val r1 = up(srv).upload(f, state(f), { states += it })
        assertTrue(r1 is UploadResult.Paused)
        val saved = (r1 as UploadResult.Paused).state
        assertEquals(1, saved.attempts)
        assertEquals(saved.serverId, states.last().serverId)
        assertEquals(2, srv.putLog.size)
        srv.failPutsAfter = Int.MAX_VALUE
        val r2 = up(srv).upload(f, saved, {})
        assertTrue(r2 is UploadResult.Done)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), srv.putLog.map { it.second }) // 0,1 once; 2..5 on the second run
        assertEquals(1, srv.createCount)
    }

    @Test fun aForgottenServerIdIsRecreated() {
        val f = file(1500)
        val srv = FakeBundleServer()
        val r = up(srv).upload(f, state(f).copy(serverId = "gone", chunkSize = 1000), {})
        assertTrue(r is UploadResult.Done)
        assertEquals(1, srv.createCount)
    }

    @Test fun transientChunkErrorsRetryThenGiveUp() {
        val f = file(900)
        var fails = 3
        val t = object : BeamTransport {
            val srv = FakeBundleServer()
            override fun create(name: String, size: Long, sha256: String) = srv.create(name, size, sha256)
            override fun putChunk(id: String, n: Int, bytes: ByteArray) { if (fails-- > 0) throw BeamHttpException(503, "busy") else srv.put(id, n, bytes) }
            override fun status(id: String) = srv.status(id)
            override fun complete(id: String) = srv.complete(id)
        }
        val slept = ArrayList<Long>()
        assertTrue(ChunkedUploader(t, sleep = { slept += it }).upload(f, state(f), {}) is UploadResult.Done)
        assertEquals(3, slept.size)
        assertTrue(slept[0] < slept[1] && slept[1] < slept[2])
        fails = 100
        val r = ChunkedUploader(t, chunkRetries = 2, sleep = {}).upload(file(900), state(file(900)), {})
        assertTrue(r is UploadResult.Paused)
    }

    @Test fun shaMismatchRestartsUnderANewId() {
        val f = file(2200)
        val srv = FakeBundleServer().also { it.failCompleteOnce = 1 }
        val r1 = up(srv).upload(f, state(f), {})
        assertTrue(r1 is UploadResult.Paused)
        val st = (r1 as UploadResult.Paused).state
        assertEquals("", st.serverId)
        assertEquals(1, st.restarts)
        assertTrue(up(srv).upload(f, st, {}) is UploadResult.Done)
        assertEquals(2, srv.createCount)
    }

    @Test fun aRefusalDropsTheBundle() {
        val f = file(100)
        val t = object : BeamTransport {
            override fun create(name: String, size: Long, sha256: String): BundleReply = throw BeamHttpException(413, "too large")
            override fun putChunk(id: String, n: Int, bytes: ByteArray) = Unit
            override fun status(id: String) = BundleReply()
            override fun complete(id: String) = BundleReply()
        }
        assertTrue(ChunkedUploader(t, sleep = {}).upload(f, state(f), {}) is UploadResult.Rejected)
    }

    @Test fun cancelStopsBetweenChunksAndKeepsState() {
        val f = file(4000)
        val srv = FakeBundleServer()
        var n = 0
        val r = up(srv).upload(f, state(f), {}, cancelled = { n++ >= 2 })
        assertTrue(r is UploadResult.Paused && r.why == "cancelled")
        assertEquals(2, srv.putLog.size)
    }

    @Test fun queueRunnerSendsOldestFirstAndDeletesWhatIsDone() {
        val q = BeamQueue(File(tmp.root, "queue"))
        val a = file(1200); val b = file(800)
        q.enqueue(a, "20260101-a"); q.enqueue(b, "20260102-b")
        assertEquals(listOf("20260101-a.zip", "20260102-b.zip"), q.pending().map { it.state.name })
        val srv = FakeBundleServer()
        val s = QueueRunner(q, up(srv)).run()
        assertEquals(2, s.sent)
        assertTrue(q.pending().isEmpty())
        assertEquals(listOf("20260101-a.zip", "20260102-b.zip"), srv.slots.values.map { it.name })
    }

    @Test fun queueKeepsProgressAcrossARun() {
        val q = BeamQueue(File(tmp.root, "queue"))
        q.enqueue(file(3000), "x")
        val srv = FakeBundleServer().also { it.failPutsAfter = 1 }
        val s = QueueRunner(q, up(srv)).run()
        assertEquals(1, s.kept)
        val e = q.pending().single()
        assertTrue(e.state.serverId.isNotEmpty())
        srv.failPutsAfter = Int.MAX_VALUE
        assertEquals(1, QueueRunner(q, up(srv)).run().sent)
        assertEquals(1, srv.createCount)
    }

    @Test fun pruneKeepsTheNewestWithinTheCaps() {
        val q = BeamQueue(File(tmp.root, "queue"))
        for (i in 1..5) q.enqueue(file(100), "2026010$i")
        assertEquals(2, q.prune(maxBytes = 1_000_000, maxCount = 3))
        assertEquals(listOf("20260103.zip", "20260104.zip", "20260105.zip"), q.pending().map { it.state.name })
        assertEquals(1, q.prune(maxBytes = 250, maxCount = 10))
        assertEquals(2, q.pending().size)
    }

    @Test fun brokenQueueEntriesAreRemoved() {
        val q = BeamQueue(File(tmp.root, "queue"))
        File(tmp.root, "queue").mkdirs()
        File(tmp.root, "queue/orphan.json").writeText("{}")
        File(tmp.root, "queue/bad.json").writeText("not json")
        File(tmp.root, "queue/bad.zip").writeText("z")
        assertTrue(q.pending().isEmpty())
        assertFalse(File(tmp.root, "queue/bad.json").exists())
    }
}

/** The real [HttpBeamTransport] against an OkHttp interceptor that plays the server. */
class HttpTransportTest {
    @get:Rule val tmp = TemporaryFolder()
    private val pairing = PairingInfo(1, "http://192.168.0.5:48310", "t".repeat(40), "pc", listOf("http://100.65.210.13:48310", "http://192.168.0.5:48310"))

    private fun client(srv: FakeBundleServer, seen: MutableList<String>, deadHost: String? = null): OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val req = chain.request()
        seen += "${req.method} ${req.url.host} ${req.url.encodedPath}"
        if (req.url.host == deadHost) throw IOException("unreachable")
        val path = req.url.encodedPath
        fun ok(r: BundleReply) = respond(req, 200, ProcJson.json.encodeToString(BundleReply.serializer(), r))
        try {
            val m = Regex("^/v1/dev/bundles(?:/([^/]+))?(?:/(chunks/(\\d+)|complete))?$").matchEntire(path)!!
            val id = m.groupValues[1]
            when {
                req.method == "POST" && id.isEmpty() -> {
                    val b = Buffer().also { req.body!!.writeTo(it) }.readUtf8()
                    val j = ProcJson.json.decodeFromString(CreateBundleRequest.serializer(), b)
                    ok(srv.create(j.name, j.size, j.sha256))
                }
                req.method == "PUT" -> { srv.put(id, m.groupValues[3].toInt(), Buffer().also { req.body!!.writeTo(it) }.readByteArray()); respond(req, 200, "{\"received\":${m.groupValues[3]}}") }
                req.method == "GET" -> ok(srv.status(id))
                req.method == "POST" && m.groupValues[2] == "complete" -> ok(srv.complete(id))
                else -> respond(req, 404, "{}")
            }
        } catch (e: BeamHttpException) {
            if (e.status == 0) throw IOException(e.message)
            respond(req, e.status, "{\"error\":{\"code\":\"x\",\"message\":\"${e.message}\"}}")
        }
    }).build()

    private fun respond(req: okhttp3.Request, code: Int, body: String): Response =
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("m").body(body.toResponseBody("application/json".toMediaType())).build()

    @Test fun fullRoundTripOverHttpPicksTheNextUrlWhenTheFirstIsDown() {
        val f = tmp.newFile().also { it.writeBytes(ByteArray(2500) { i -> i.toByte() }) }
        val srv = FakeBundleServer()
        val seen = ArrayList<String>()
        val t = HttpBeamTransport(pairing, pairing.allUrls(), client(srv, seen, deadHost = "100.65.210.13"))
        val r = ChunkedUploader(t, sleep = {}).upload(f, UploadState(f.name, f.length(), Sha256.hex(f)), {})
        assertTrue(r.toString(), r is UploadResult.Done)
        assertTrue(srv.slots.values.single().complete)
        assertTrue(seen.first().startsWith("POST 100.65.210.13 /v1/dev/bundles"))
        assertTrue(seen.any { it == "PUT 192.168.0.5 /v1/dev/bundles/b0/chunks/2" })
    }

    @Test fun serverErrorsBecomeTypedExceptions() {
        val srv = FakeBundleServer()
        val t = HttpBeamTransport(pairing, pairing.allUrls().take(1), client(srv, ArrayList()))
        try { t.status("nope"); throw AssertionError("expected 404") } catch (e: BeamHttpException) {
            assertEquals(404, e.status); assertEquals("no such bundle", e.message); assertFalse(e.retryable)
        }
        assertTrue(BeamHttpException(0, "x").retryable && BeamHttpException(503, "x").retryable && !BeamHttpException(413, "x").retryable)
    }

    @Test fun onlyTheGivenUrlsAreEverContacted() {
        val srv = FakeBundleServer()
        val seen = ArrayList<String>()
        val t = HttpBeamTransport(pairing, listOf("http://192.168.0.5:48310"), client(srv, seen))
        t.create("a.zip", 10, "00")
        assertTrue(seen.all { it.contains("192.168.0.5") })
    }
}
