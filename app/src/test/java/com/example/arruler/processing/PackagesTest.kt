package com.example.arruler.processing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class JobPackageTest {
    @get:Rule val tmp = TemporaryFolder()

    private val meta = PackageMeta("1.0.0", "2026-10-03T12:00:00Z", DeviceSummary("MID", 31, 5600, 8, "SM8550"), ObjectQuality.FINE)

    private fun cloud(n: Int) = CloudData(
        FloatArray(n * 3) { it * 0.01f - 3f }, IntArray(n) { it % 70000 }, IntArray(n) { (it * 7) % 300 }
    )

    @Test fun plyIs15BytesPerVertexAndRoundTrips() {
        val c = cloud(5000)
        val bos = ByteArrayOutputStream(); PlyCloud.write(bos, c)
        val header = "ply\nformat binary_little_endian 1.0\nelement vertex 5000\nproperty float x\nproperty float y\n" +
            "property float z\nproperty ushort hits\nproperty uchar confidence\nend_header\n"
        assertEquals(header.length + 5000 * 15, bos.size())
        val r = PlyCloud.read(ByteArrayInputStream(bos.toByteArray()))
        assertArrayEquals(c.xyz, r.xyz, 0f)
        assertEquals(c.hits.map { it.coerceIn(0, 65535) }, r.hits.toList())
        assertEquals(c.confidence.map { it.coerceIn(0, 255) }, r.confidence.toList())
    }

    @Test fun plyRejectsTruncated() {
        val bos = ByteArrayOutputStream(); PlyCloud.write(bos, cloud(10))
        val b = bos.toByteArray().copyOf(bos.size() - 5)
        try { PlyCloud.read(ByteArrayInputStream(b)); fail() } catch (e: PackageFormatException) { }
    }

    @Test fun pointJobRoundTripAndManifestSchema() {
        val f = tmp.newFile("job.zip")
        val c = cloud(300)
        JobPackage.writePointJob(f, JobType.OBJECT_MESH, c, meta)
        val back = JobPackage.read(f)
        assertArrayEquals(c.xyz, back.cloud!!.xyz, 0f)
        val m = back.manifest
        assertEquals(1, m.schema); assertEquals("object_mesh", m.jobType); assertEquals("1.0.0", m.appVersion)
        assertEquals("meters", m.units); assertEquals("2026-10-03T12:00:00Z", m.created)
        assertEquals("FINE", m.quality); assertEquals(3, m.voxelMm); assertEquals(300, m.pointCount)
        assertEquals("ARCore world", m.coordinates.frame); assertEquals("+Y", m.coordinates.up)
        assertTrue(m.coordinates.poseLayout.contains("column-major"))
        assertEquals("MID", m.device!!.tier)
    }

    @Test fun manifestJsonKeysAreSnakeCase() {
        val f = tmp.newFile("job.zip")
        JobPackage.writePointJob(f, JobType.SCAN_ANALYZE, cloud(3), meta)
        val text = java.util.zip.ZipFile(f).use { z -> z.getInputStream(z.getEntry("manifest.json")).readBytes().toString(Charsets.UTF_8) }
        for (k in listOf("\"schema\":1", "\"job_type\":\"scan_analyze\"", "\"app_version\"", "\"voxel_mm\":3", "\"quality\":\"FINE\"", "\"point_count\":3", "\"coordinates\"")) {
            assertTrue("$k in $text", text.contains(k))
        }
    }

    @Test fun scanJobWithoutQualityOmitsIt() {
        val f = tmp.newFile("job.zip")
        JobPackage.writePointJob(f, JobType.SCAN_ANALYZE, cloud(3), meta.copy(quality = null))
        val m = JobPackage.readManifest(f)
        assertNull(m.quality); assertNull(m.voxelMm)
    }

    @Test fun photoJobRoundTrip() {
        val frames = (1..3).map { i ->
            val jpg = tmp.newFile("src$i.jpg").also { it.writeBytes(ByteArray(100 + i) { b -> (b + i).toByte() }) }
            PhotoFrame(jpg, 1000L * i, FloatArray(16) { it + i.toFloat() }, 1500f, 1501f, 960f, 540f, 1920, 1080)
        }
        val f = tmp.newFile("photo.zip")
        JobPackage.writePhotoJob(f, frames, meta.copy(quality = null))
        val back = JobPackage.read(f)
        assertEquals(listOf("images/000001.jpg", "images/000002.jpg", "images/000003.jpg"), back.imageNames)
        assertEquals("photogrammetry", back.manifest.jobType)
        assertEquals("DETAILED", back.manifest.quality)
        assertEquals(3, back.manifest.imageCount)
        val p = back.poses!!.images[1]
        assertEquals("images/000002.jpg", p.file); assertEquals(2000L, p.timestampNs)
        assertEquals(16, p.pose.size); assertEquals(2f, p.pose[0], 0f)
        assertEquals(1500f, p.fx, 0f); assertEquals(1920, p.width)
    }

    @Test fun pointWriterRejectsPhotogrammetry() {
        try { JobPackage.writePointJob(tmp.newFile("x.zip"), JobType.PHOTOGRAMMETRY, cloud(1), meta); fail() }
        catch (e: IllegalArgumentException) { }
    }

    @Test fun readerRejectsMissingManifest() {
        val f = tmp.newFile("bad.zip")
        ZipOutputStream(f.outputStream()).use { it.putNextEntry(ZipEntry("cloud.ply")); it.closeEntry() }
        try { JobPackage.read(f); fail() } catch (e: PackageFormatException) { }
    }

    @Test fun readerRejectsNewerSchema() {
        val f = tmp.newFile("new.zip")
        ZipOutputStream(f.outputStream()).use {
            it.putNextEntry(ZipEntry("manifest.json"))
            it.write("""{"schema":99,"job_type":"scan_analyze","app_version":"x","created":"c"}""".toByteArray())
            it.closeEntry()
        }
        try { JobPackage.readManifest(f); fail() } catch (e: PackageFormatException) { assertTrue(e.message!!.contains("99")) }
    }

    @Test fun readerRejectsUnknownJobType() {
        val f = tmp.newFile("t.zip")
        ZipOutputStream(f.outputStream()).use {
            it.putNextEntry(ZipEntry("manifest.json"))
            it.write("""{"schema":1,"job_type":"bogus","app_version":"x","created":"c"}""".toByteArray()); it.closeEntry()
        }
        try { JobPackage.readManifest(f); fail() } catch (e: PackageFormatException) { }
    }

    @Test fun cloudDataValidatesSizes() {
        try { CloudData(FloatArray(6), IntArray(1), IntArray(2)); fail() } catch (e: IllegalArgumentException) { }
    }

    @Test fun jobTypeWireRoundTrip() {
        for (t in JobType.entries) assertEquals(t, JobType.fromWire(t.wire))
        assertNull(JobType.fromWire("nope"))
    }

    @Test fun uploadEstimate() {
        assertEquals(150_000L, JobEstimate(pointCount = 10_000).uploadBytes(JobType.OBJECT_MESH))
        assertEquals(120_000_000L, JobEstimate(imageCount = 60).uploadBytes(JobType.PHOTOGRAMMETRY))
        assertEquals(5L, JobEstimate(imageCount = 60, imageBytes = 5).uploadBytes(JobType.PHOTOGRAMMETRY))
    }
}

class ResultPackageTest {
    @get:Rule val tmp = TemporaryFolder()

    private val result = ResultJson(
        jobType = "scan_analyze",
        measures = Measures(
            areaM2 = Estimate(11.5, 12.5, 12.0), perimeterM = Estimate.exact(14.0), heightM = Estimate(2.4, 2.6, 2.5),
            volumeM3 = Estimate(27.0, 33.0, 30.0), volumeVariantsM3 = mapOf("bounding_box" to 31.0, "mesh" to 29.5),
            wallCount = 4, objectDims = ObjectDims(0.2, 0.1, 0.05)
        ),
        stats = ProcessingStats("pc", 4200, mapOf("server" to "0.1", "open3d" to "0.19"))
    )

    @Test fun roundTripWithOptionalFiles() {
        val f = tmp.newFile("r.zip")
        ResultPackage.write(f, result, mapOf("mesh.ply" to byteArrayOf(1, 2, 3), "texture.png" to byteArrayOf(9)))
        val c = ResultPackage.read(f)
        assertEquals(result.measures, c.result.measures)
        assertEquals(4200L, c.result.stats.durationMs); assertEquals("pc", c.result.stats.backend)
        assertEquals(listOf("mesh.ply", "texture.png"), c.result.files)
        assertEquals(setOf("result.json", "mesh.ply", "texture.png"), c.entries)
        assertArrayEquals(byteArrayOf(1, 2, 3), ResultPackage.readEntry(f, "mesh.ply"))
        assertNull(ResultPackage.readEntry(f, "mesh.obj"))
    }

    @Test fun minimalResult() {
        val f = tmp.newFile("m.zip")
        ResultPackage.write(f, result.copy(measures = Measures()))
        val c = ResultPackage.read(f)
        assertNull(c.result.measures.areaM2); assertTrue(c.result.files.isEmpty())
    }

    @Test fun resultJsonKeys() {
        val text = ProcJson.json.encodeToString(ResultJson.serializer(), result)
        for (k in listOf("\"area_m2\"", "\"low\":11.5", "\"recommended\":12.0", "\"wall_count\":4", "\"duration_ms\":4200", "\"volume_variants_m3\"", "\"object_dims\"", "\"length_m\"")) {
            assertTrue("$k in $text", text.contains(k))
        }
    }

    @Test fun planesFileRoundTrip() {
        val f = tmp.newFile("p.zip")
        val planes = PlanesFile(planes = listOf(PlaneDto("FLOOR", listOf(0f, 1f, 0f), 0f, listOf(1f, 0f, 1f), 900, listOf(listOf(0f, 0f, 0f)))))
        ResultPackage.write(f, result, mapOf("planes.json" to ProcJson.json.encodeToString(PlanesFile.serializer(), planes).toByteArray()))
        assertEquals("FLOOR", ResultPackage.readPlanes(f)!!.planes[0].kind)
    }

    @Test fun rejectsUnknownOptionalFile() {
        try { ResultPackage.write(tmp.newFile("x.zip"), result, mapOf("evil.exe" to byteArrayOf())); fail() }
        catch (e: IllegalArgumentException) { }
    }

    @Test fun rejectsMissingResultJson() {
        val f = tmp.newFile("e.zip")
        ZipOutputStream(f.outputStream()).use { it.putNextEntry(ZipEntry("mesh.ply")); it.closeEntry() }
        try { ResultPackage.read(f); fail() } catch (e: PackageFormatException) { }
    }

    @Test fun extractIsZipSlipSafe() {
        val f = tmp.newFile("s.zip")
        ZipOutputStream(f.outputStream()).use { it.putNextEntry(ZipEntry("../evil.txt")); it.write(1); it.closeEntry() }
        try { ResultPackage.extract(f, tmp.newFolder("out")); fail() } catch (e: PackageFormatException) { }
    }

    @Test fun extractWritesFiles() {
        val f = tmp.newFile("x.zip")
        ResultPackage.write(f, result, mapOf("mesh.obj" to "v 0 0 0".toByteArray()))
        val m = ResultPackage.extract(f, tmp.newFolder("o2"))
        assertEquals("v 0 0 0", m["mesh.obj"]!!.readText()); assertNotNull(m["result.json"])
        assertFalse(m.containsKey("mesh.ply"))
    }
}

class PairingTest {
    private val token = "a".repeat(32)
    private fun qr(url: String, tok: String = token, v: Int = 1) = """{"v":$v,"url":"$url","token":"$tok","name":"RYZEN-PC"}"""

    @Test fun parsesValidLanQr() {
        val p = PairingInfo.parse(qr("http://192.168.1.20:8765/")).getOrThrow()
        assertEquals("http://192.168.1.20:8765", p.url); assertEquals("RYZEN-PC", p.name)
    }

    @Test fun parsesHttps() = assertTrue(PairingInfo.parse(qr("https://pc.example.com")).isSuccess)

    @Test fun rejectsShortToken() = assertTrue(PairingInfo.parse(qr("https://pc.example.com", "short")).isFailure)

    @Test fun rejectsWrongVersion() = assertTrue(PairingInfo.parse(qr("https://pc.example.com", v = 2)).isFailure)

    @Test fun rejectsGarbage() = assertTrue(PairingInfo.parse("hello").isFailure)

    @Test fun rejectsPublicHttp() {
        assertTrue(PairingInfo.parse(qr("http://example.com")).isFailure)
        assertTrue(PairingInfo.parse(qr("http://8.8.8.8")).isFailure)
    }

    @Test fun urlPolicyPrivateRanges() {
        for (ok in listOf("10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.1", "192.168.0.5"))
            assertTrue(ok, UrlPolicy.isAllowed("http://$ok:8000"))
        for (bad in listOf("172.15.0.1", "172.32.0.1", "192.169.0.1", "11.0.0.1", "127.0.0.1", "169.254.1.1", "192.168.0", "192.168.0.256", "localhost", "pc.local", "pc.example.com"))
            assertFalse(bad, UrlPolicy.isAllowed("http://$bad:8000"))
    }

    @Test fun urlPolicyHttpsAnywhereAndJunk() {
        assertTrue(UrlPolicy.isAllowed("https://8.8.8.8"))
        assertFalse(UrlPolicy.isAllowed("ftp://192.168.1.1"))
        assertFalse(UrlPolicy.isAllowed("not a url"))
    }

    @Test fun statusDtoMapping() {
        fun st(s: String) = ProcJson.json.decodeFromString<StatusDto>(s).toStatus()
        assertEquals(JobStatus.Queued(2), st("""{"id":"a","state":"queued","position":2}"""))
        assertEquals(JobStatus.Running(0.5f, "mesh"), st("""{"id":"a","state":"running","progress":0.5,"stage":"mesh"}"""))
        assertEquals(JobStatus.Done, st("""{"id":"a","state":"done","progress":1}"""))
        assertEquals(JobStatus.Failed("boom"), st("""{"id":"a","state":"failed","error":"boom"}"""))
        assertEquals(JobStatus.Cancelled, st("""{"state":"cancelled"}"""))
        assertTrue(st("""{"state":"weird"}""") is JobStatus.Failed)
    }
}

class TailnetPolicyTest {
    private fun ok(host: String) = UrlPolicy.isAllowed("http://$host:8765")

    @Test fun cgnatBoundaries() {
        assertFalse(ok("100.63.255.255"))
        assertTrue(ok("100.64.0.0"))
        assertTrue(ok("100.100.100.100"))
        assertTrue(ok("100.127.255.255"))
        assertFalse(ok("100.128.0.0"))
        assertFalse(ok("101.64.0.1"))
        assertFalse(ok("99.64.0.1"))
    }

    @Test fun publicIpsStillRejected() {
        assertFalse(ok("8.8.8.8"))
        assertFalse(ok("203.0.113.9"))
    }

    @Test fun tailnetIpv6Prefix() {
        assertTrue(UrlPolicy.isAllowed("http://[fd7a:115c:a1e0::1]:8765"))
        assertTrue(UrlPolicy.isAllowed("http://[fd7a:115c:a1e0:ab12:4843:cd96:6258:1234]:8765"))
        assertTrue(UrlPolicy.isAllowed("http://[fd7a:115c:a1e0:ffff:ffff:ffff:ffff:ffff]/"))
        assertFalse(UrlPolicy.isAllowed("http://[fd7a:115c:a1e1::1]:8765"))
        assertFalse(UrlPolicy.isAllowed("http://[fd7a:115c:a1df::1]:8765"))
        assertFalse(UrlPolicy.isAllowed("http://[2001:db8::1]:8765"))
        assertFalse(UrlPolicy.isAllowed("http://[::1]:8765"))
    }

    @Test fun magicDnsNames() {
        assertTrue(ok("rxmoi"))
        assertTrue(ok("Rxmoi"))
        assertTrue(ok("my-pc-2"))
        assertTrue(ok("example.ts.net"))
        assertTrue(ok("rxmoi.tail1234.ts.net"))
        assertFalse(ok("ts.net"))
        assertFalse(ok("evil-ts.net"))
        assertFalse(ok("example.ts.net.evil.com"))
        assertFalse(ok("example.com"))
        assertFalse(ok("localhost"))
    }

    @Test fun httpsStillAnywhere() = assertTrue(UrlPolicy.isAllowed("https://pc.example.com"))
}

class PairingUrlsTest {
    private val token = "b".repeat(32)
    private fun json(url: String?, urls: List<String>?): String {
        val u = url?.let { """"url":"$it",""" } ?: ""
        val us = urls?.let { """"urls":[${it.joinToString(",") { x -> "\"$x\"" }}],""" } ?: ""
        return """{"v":1,$u$us"token":"$token","name":"Rxmoi"}"""
    }

    @Test fun legacyUrlOnlyStillParses() {
        val p = PairingInfo.parse(json("http://192.168.0.247:8765/", null)).getOrThrow()
        assertEquals("http://192.168.0.247:8765", p.url)
        assertEquals(listOf("http://192.168.0.247:8765"), p.allUrls())
        assertTrue(p.urls.isEmpty())
    }

    @Test fun urlsListKeepsOrderAndFirstBecomesUrl() {
        val p = PairingInfo.parse(json("http://192.168.0.247:8765", listOf("http://100.101.102.103:8765/", "http://192.168.0.247:8765", "https://x.trycloudflare.com"))).getOrThrow()
        assertEquals(listOf("http://100.101.102.103:8765", "http://192.168.0.247:8765", "https://x.trycloudflare.com"), p.allUrls())
        assertEquals("http://100.101.102.103:8765", p.url)
    }

    @Test fun urlsOnlyWithoutUrl() {
        val p = PairingInfo.parse(json(null, listOf("http://rxmoi:8765"))).getOrThrow()
        assertEquals("http://rxmoi:8765", p.url)
    }

    @Test fun anyDisallowedEntryFailsTheWholeCode() {
        assertTrue(PairingInfo.parse(json("http://192.168.0.247:8765", listOf("http://8.8.8.8:8765"))).isFailure)
    }

    @Test fun noUrlAtAllFails() = assertTrue(PairingInfo.parse(json(null, null)).isFailure)

    @Test fun tryOrderPutsLastGoodFirst() {
        val u = listOf("a", "b", "c")
        assertEquals(listOf("a", "b", "c"), UrlOrder.order(u, null))
        assertEquals(listOf("c", "a", "b"), UrlOrder.order(u, "c"))
        assertEquals(listOf("a", "b", "c"), UrlOrder.order(u, "zzz"))
    }
}
