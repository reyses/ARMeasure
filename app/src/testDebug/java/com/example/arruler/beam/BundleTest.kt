package com.example.arruler.beam

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class BundleTest {
    @get:Rule val tmp = TemporaryFolder()
    private val id = BundleIdentity("com.example.arruler", "1.0.0", "abc1234", "Google Pixel 11 Pro", 37)

    private fun session(clock: FakeClock = FakeClock()): BeamSession {
        val s = BeamSession.create(File(tmp.root, "sessions"), "scan", "kitchen", clock, rnd = 0xbeef)
        s.timeline.event(EventTypes.SESSION_START)
        s.timeline.event(EventTypes.TAP, mapOf("x" to 1))
        s.timeline.event(EventTypes.ERROR, mapOf("message" to "boom", "stack" to "at x"))
        s.snapDir.mkdirs()
        for (i in 1..3) File(s.snapDir, "snap-%013d-%d.jpg".format(1000L + i, i)).writeBytes(ByteArray(100 * i) { 7 })
        return s
    }

    private fun policy(video: Boolean = true, snaps: Boolean = true, full: Boolean = false) =
        BeamPolicy(includeCameraVideo = video, includeScreenSnapshots = snaps, fullScreenRecording = full)

    @Test fun sessionIdIsSortableAndSafe() {
        val a = BeamSession.newId("Scan 3D!", 1_700_000_000_000, 0x1f)
        assertEquals("20231114-221320-scan3d-001f", a)
        assertTrue(BeamSession.newId("", 0, 1).contains("-run-"))
    }

    @Test fun closedSessionLoadsBackWithCountsAndSnapshots() {
        val s = session()
        s.attach(BundleRoles.EXPORT, File(tmp.root, "surfaces.obj"))
        s.close("finish")
        val rec = BeamSession.load(s.dir)!!
        assertEquals("kitchen", rec.meta.label)
        assertEquals("finish", rec.closed!!.reason)
        assertEquals(3, rec.eventTotal)
        assertEquals(1, rec.eventCounts[EventTypes.ERROR])
        assertEquals(3, rec.snapshots.size)
        assertEquals(1, rec.meta.attachments.size)
        assertTrue(BeamSession.orphans(File(tmp.root, "sessions")).isEmpty())
    }

    @Test fun anUnclosedSessionIsAnOrphan() {
        val s = session()
        s.timeline.close()
        assertEquals(listOf(s.dir.name), BeamSession.orphans(File(tmp.root, "sessions")).map { it.name })
        assertTrue(BeamSession.orphans(File(tmp.root, "sessions"), exclude = setOf(s.dir.name)).isEmpty())
        assertNull(BeamSession.load(File(tmp.root, "nothing")))
    }

    @Test fun planKeepsPriorityOrderWithinBudgetAndListsWhatItSkips() {
        val big = tmp.newFile("big.mp4").also { it.writeBytes(ByteArray(1000)) }
        val small = tmp.newFile("small.txt").also { it.writeBytes(ByteArray(10)) }
        val c = listOf(
            Candidate("recordings/big.mp4", big, null, BundleRoles.ARCORE_RECORDING, 6),
            Candidate("timeline.jsonl", small, null, BundleRoles.TIMELINE, 1),
            Candidate("gone.bin", File(tmp.root, "gone.bin"), null, BundleRoles.EXPORT, 4),
            Candidate("crash.txt", null, ByteArray(20), BundleRoles.CRASH, 1),
        )
        val p = BundlePlanner.plan(c, budget = 500)
        assertEquals(listOf("timeline.jsonl", "crash.txt"), p.included.map { it.entryName })
        assertEquals(setOf("recordings/big.mp4" to "size budget 0 MB", "gone.bin" to "missing"), p.skipped.map { it.name to it.why }.toSet())
        assertEquals(2, BundlePlanner.plan(c, budget = 5000).included.count { it.priority == 1 })
        assertEquals(3, BundlePlanner.plan(c, budget = 5000).included.size)
    }

    @Test fun safeNamesCannotEscapeAndStayUnique() {
        val taken = HashSet<String>()
        assertEquals("passwd", BundlePlanner.safeName("../../etc/passwd", taken))
        assertEquals("passwd-1", BundlePlanner.safeName("..\\x\\passwd", taken))
        assertEquals("file", BundlePlanner.safeName("..", HashSet()))
        assertEquals("a_b.obj", BundlePlanner.safeName("a:b.obj", HashSet()))
    }

    @Test fun policySwitchesSelectTheFiles() {
        val s = session()
        val rec1 = File(tmp.root, "recording.mp4").also { it.writeBytes(ByteArray(300)) }
        val scr = File(tmp.root, "screen.mp4").also { it.writeBytes(ByteArray(200)) }
        val obj = File(tmp.root, "surfaces.obj").also { it.writeText("v 0 0 0") }
        s.attach(BundleRoles.ARCORE_RECORDING, rec1); s.attach(BundleRoles.SCREEN_RECORDING, scr); s.attach(BundleRoles.EXPORT, obj)
        s.close("analyze_done")
        val rec = BeamSession.load(s.dir)!!
        val texts = BundleTexts("log", "diag", "crash text")
        fun names(p: BeamPolicy) = SessionBundler.candidates(rec, texts, p).map { it.entryName }.toSet()
        val all = names(policy(full = true))
        assertTrue(all.containsAll(setOf("timeline.jsonl", "crash.txt", "diagnostics.txt", "logcat.txt", "recordings/recording.mp4", "screen/screen.mp4", "exports/surfaces.obj")))
        assertEquals(3, all.count { it.startsWith("snapshots/") })
        val lean = names(policy(video = false, snaps = false))
        assertFalse(lean.any { it.startsWith("snapshots/") || it.startsWith("recordings/") || it.startsWith("screen/") })
        assertTrue("exports/surfaces.obj" in lean)
    }

    @Test fun buildWritesAZipWithManifestFirst() {
        val s = session()
        val video = File(tmp.root, "rec.mp4").also { it.writeBytes(ByteArray(5000) { i -> i.toByte() }) }
        s.attach(BundleRoles.ARCORE_RECORDING, video)
        s.close("analyze_done")
        val rec = BeamSession.load(s.dir)!!
        val out = File(tmp.root, "out/b.zip")
        val (m, sha) = SessionBundler.build(rec, id, BundleTexts("logcat body", "diag body", null), policy(), out, nowMs = 1_700_000_100_000)
        assertEquals(64, sha.length)
        assertEquals("analyze_done", m.reason)
        assertEquals(3, m.snapshotCount)
        assertEquals(3, m.eventTotal)
        ZipFile(out).use { z ->
            assertEquals("manifest.json", z.entries().nextElement().name)
            val j = Json.parseToJsonElement(z.getInputStream(z.getEntry("manifest.json")).readBytes().decodeToString()).jsonObject
            assertEquals(s.id, j["session_id"]!!.jsonPrimitive.content)
            assertEquals("abc1234", j["commit"]!!.jsonPrimitive.content)
            assertEquals("scan", j["kind"]!!.jsonPrimitive.content)
            assertNotNull(j["event_counts"])
            assertEquals(5000, z.getEntry("recordings/rec.mp4").size)
            assertEquals("logcat body", z.getInputStream(z.getEntry("logcat.txt")).readBytes().decodeToString())
            assertNull(z.getEntry("crash.txt"))
            assertEquals(3, z.entries().asSequence().count { it.name.startsWith("snapshots/") })
            assertEquals(File(s.dir, "timeline.jsonl").readText(), z.getInputStream(z.getEntry("timeline.jsonl")).readBytes().decodeToString())
        }
    }

    @Test fun anOrphanIsBundledAsCrashAndAlwaysWorthSending() {
        val s = session()
        s.timeline.close()
        val rec = BeamSession.load(s.dir)!!
        assertTrue(SessionBundler.worthSending(rec))
        val m = SessionBundler.manifest(rec, id, BundlePlan(emptyList(), emptyList()), 5L)
        assertEquals("crash", m.reason)
    }

    @Test fun anEmptyAppSessionIsNotWorthABundle() {
        val c = FakeClock()
        val s = BeamSession.create(File(tmp.root, "sessions"), "app", "", c)
        s.timeline.event(EventTypes.SESSION_START); s.timeline.event(EventTypes.APP_START); s.timeline.event(EventTypes.FPS)
        s.timeline.event(EventTypes.APP_STOP); s.close("background")
        assertFalse(SessionBundler.worthSending(BeamSession.load(s.dir)!!))
        val s2 = BeamSession.create(File(tmp.root, "sessions"), "app", "", c, rnd = 2)
        s2.timeline.event(EventTypes.APP_START); s2.timeline.event(EventTypes.MODE_CHANGE); s2.close("background")
        assertTrue(SessionBundler.worthSending(BeamSession.load(s2.dir)!!))
    }
}

class SnapAndRecordGeometryTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun snapTargetIs540WideAspectKept() {
        assertEquals(540 to 1200, SnapGeometry.target(1080, 2400))
        assertEquals(400 to 400, SnapGeometry.target(400, 400))
        assertEquals(540 to 540, SnapGeometry.target(0, 0))
    }

    @Test fun ringKeepsTheNewestN() {
        val r = SnapRing(File(tmp.root, "snaps"), max = 3)
        for (i in 1..7) r.add(1000L + i, i.toLong(), ByteArray(4))
        assertEquals(listOf(1005L, 1006L, 1007L), r.list().map { it.name.substring(5, 18).toLong() })
        assertEquals("snap-0000000001001-1.jpg", r.file(1001, 1).name)
    }

    @Test fun recordingSizeIs720ShortSideEven() {
        assertEquals(720 to 1600, ScreenRecordGeometry.size(1080, 2400))
        assertEquals(1600 to 720, ScreenRecordGeometry.size(2400, 1080))
        assertEquals(480 to 800, ScreenRecordGeometry.size(480, 800))
        val (w, h) = ScreenRecordGeometry.size(1008, 2244)
        assertTrue(w % 2 == 0 && h % 2 == 0 && minOf(w, h) == 720)
    }
}

class UploadGateTest {
    private val tail = "http://100.65.210.13:48310"
    private val lan = "http://192.168.0.247:48310"
    private val tunnel = "https://abc.trycloudflare.com"
    private val urls = listOf(tail, lan, tunnel)

    @Test fun tunnelIsRefusedUnlessAllowed() {
        assertEquals(listOf(tail, lan), UploadGate.allowedUrls(BeamPolicy(), urls))
        assertEquals(urls, UploadGate.allowedUrls(BeamPolicy(allowOverTunnel = true), urls))
        assertEquals(emptyList<String>(), UploadGate.allowedUrls(BeamPolicy(), listOf(tunnel)))
    }

    @Test fun reasonsInOrder() {
        val p = BeamPolicy()
        assertNull(UploadGate.blockedReason(p, true, urls, metered = false))
        assertEquals("Beam to PC is off", UploadGate.blockedReason(p.copy(beamToPc = false), true, urls, false))
        assertEquals("no PC paired", UploadGate.blockedReason(p, false, urls, false))
        assertEquals("no network", UploadGate.blockedReason(p, true, urls, null))
        // small bundles go over a tailnet / LAN URL on mobile data even with Wi-Fi only
        assertNull(UploadGate.blockedReason(p, true, urls, metered = true))
        assertTrue(UploadGate.blockedReason(p, true, urls, metered = true, bytes = 30L * 1024 * 1024)!!.startsWith("waiting for Wi-Fi"))
        assertNull(UploadGate.blockedReason(p.copy(allowLargeOnMobile = true), true, urls, metered = true, bytes = 30L * 1024 * 1024))
        assertNull(UploadGate.blockedReason(p.copy(wifiOnly = false), true, urls, metered = true, bytes = 30L * 1024 * 1024))
        assertTrue(UploadGate.blockedReason(p, true, listOf(tunnel), false)!!.contains("tunnel"))
    }

    @Test fun mobileDataUrlsBySizeAndKind() {
        val p = BeamPolicy(allowOverTunnel = true)
        val big = 21L * 1024 * 1024
        val small = 20L * 1024 * 1024
        assertEquals(listOf(tail, lan), UploadGate.urlsFor(p, urls, small, metered = true)) // the public tunnel never on mobile with Wi-Fi only
        assertEquals(emptyList<String>(), UploadGate.urlsFor(p, urls, big, metered = true))
        assertEquals(listOf(tail, lan), UploadGate.urlsFor(p.copy(allowLargeOnMobile = true), urls, big, metered = true))
        assertEquals(urls, UploadGate.urlsFor(p, urls, big, metered = false)) // Wi-Fi: everything allowed
        assertEquals(urls, UploadGate.urlsFor(p.copy(wifiOnly = false), urls, big, metered = true))
        assertEquals(emptyList<String>(), UploadGate.urlsFor(p, urls, small, metered = null))
        assertEquals(listOf(tail, lan), UploadGate.urlsFor(BeamPolicy(), urls, small, metered = true))
    }

    @Test fun needsWifiOnlyForLargeBundlesWithBothSwitchesDefault() {
        assertTrue(UploadGate.needsWifi(BeamPolicy(), 21L * 1024 * 1024))
        assertFalse(UploadGate.needsWifi(BeamPolicy(), 20L * 1024 * 1024))
        assertFalse(UploadGate.needsWifi(BeamPolicy(allowLargeOnMobile = true), 500L * 1024 * 1024))
        assertFalse(UploadGate.needsWifi(BeamPolicy(wifiOnly = false), 500L * 1024 * 1024))
    }
}
