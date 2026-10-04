package com.example.arruler.devlink

import com.example.arruler.processing.ProcJson
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateDecisionTest {
    private fun info(commit: String = "abc1234", code: Int = 1, url: String = "/v1/dev/apk/ARMeasure-debug-abc1234.apk", sha: String = "ff") =
        ApkInfo(code, "1.0.0", commit, 100, sha, url)

    @Test fun sameCommitIsUpToDate() {
        assertEquals(UpdateCheck.UpToDate("abc1234"), UpdateDecision.decide("abc1234", 1, info()))
        assertEquals("Up to date (commit abc1234)", UpdateDecision.upToDateText("abc1234"))
    }

    @Test fun differentCommitIsAvailable() {
        val r = UpdateDecision.decide("1111111", 1, info())
        assertTrue(r is UpdateCheck.Available)
    }

    @Test fun higherVersionCodeWinsEvenWithSameCommit() {
        assertTrue(UpdateDecision.decide("abc1234", 1, info(code = 2)) is UpdateCheck.Available)
    }

    @Test fun lowerRemoteVersionCodeWithSameCommitIsUpToDate() {
        assertTrue(UpdateDecision.decide("abc1234", 5, info(code = 1)) is UpdateCheck.UpToDate)
    }

    @Test fun commitPrefixesOfDifferentLengthMatch() {
        assertTrue(UpdateDecision.sameCommit("abc1234", "abc1234def"))
        assertFalse(UpdateDecision.sameCommit("abc1234", "abc1235"))
        assertFalse(UpdateDecision.sameCommit("abc12", "abc12"))
    }

    @Test fun unknownInstalledCommitNeverMatches() {
        assertTrue(UpdateDecision.decide("unknown", 1, info()) is UpdateCheck.Available)
        assertFalse(UpdateDecision.sameCommit("unknown", "unknown"))
    }

    @Test fun unusableAnswers() {
        assertTrue(UpdateDecision.decide("abc1234", 1, info(sha = "")) is UpdateCheck.Unusable)
        assertTrue(UpdateDecision.decide("abc1234", 1, info(commit = "")) is UpdateCheck.Unusable)
        assertTrue(UpdateDecision.decide("abc1234", 1, info(url = "http://evil/x.apk")) is UpdateCheck.Unusable)
        assertTrue(UpdateDecision.decide("abc1234", 1, info(url = "/v1/dev/apk/../../etc/passwd")) is UpdateCheck.Unusable)
        assertTrue(UpdateDecision.decide("abc1234", 1, info(url = "/v1/dev/apk/")) is UpdateCheck.Unusable)
    }

    @Test fun apkInfoParsesServerJson() {
        val j = """{"versionCode":1,"versionName":"1.0.0","commit":"abc1234","size":12345,"sha256":"AB","url":"/v1/dev/apk/x.apk","extra":true}"""
        val a = ProcJson.json.decodeFromString<ApkInfo>(j)
        assertEquals(12345L, a.size); assertEquals("abc1234", a.commit); assertEquals("/v1/dev/apk/x.apk", a.url)
    }
}

class Sha256Test {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun knownVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc".toByteArray()))
    }

    @Test fun fileHashAndVerify() {
        val f = tmp.newFile("a.apk"); f.writeText("abc")
        val h = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertEquals(h, Sha256.hex(f))
        assertTrue(Sha256.verify(f, h, 3))
        assertTrue(Sha256.verify(f, h.uppercase(), 0))
        assertFalse(Sha256.verify(f, h, 4))
        assertFalse(Sha256.verify(f, "00".repeat(32), 3))
        assertFalse(Sha256.verify(f, "", 3))
        assertFalse(Sha256.verify(tmp.root.resolve("missing"), h, 3))
    }
}

class DevUploadTest {
    @Test fun fieldsInOrderAndKindValidated() {
        assertEquals(
            listOf("device" to "Google Pixel", "app" to "com.example.arruler", "commit" to "abc1234", "kind" to "crash"),
            DevUpload.fields("Google Pixel", "com.example.arruler", "abc1234", "crash"),
        )
        for (k in DevUpload.KINDS) DevUpload.fields("d", "a", "c", k)
        try { DevUpload.fields("d", "a", "c", "bogus"); throw AssertionError("expected failure") } catch (e: IllegalArgumentException) { /* expected */ }
    }

    @Test fun multipartHasAllFieldsAndFilePart() {
        val body = DevUpload.body(DevUpload.fields("Pixel", "pkg", "abc1234", "logs"), "logs-abc1234-20261003-101500.txt", "line1\nline2\n".toByteArray())
        val names = body.parts.map { p -> Regex("name=\"([^\"]+)\"").find(p.headers!!["Content-Disposition"]!!)!!.groupValues[1] }
        assertEquals(listOf("device", "app", "commit", "kind", "file"), names)
        val buf = Buffer(); body.writeTo(buf)
        val text = buf.readUtf8()
        assertTrue(text.contains("filename=\"logs-abc1234-20261003-101500.txt\""))
        assertTrue(text.contains("line1\nline2\n"))
        assertTrue(text.contains("\r\n\r\nlogs\r\n"))
        assertTrue(body.contentType().toString().startsWith("multipart/form-data"))
    }

    @Test fun fileNameIsUtcStamped() {
        assertEquals("crash-abc1234-19700101-000000.txt", DevUpload.fileName("crash", "abc1234", 0))
    }

    @Test fun receiptParses() {
        assertEquals("L-17", ProcJson.json.decodeFromString<DevUpload.Receipt>("""{"id":"L-17"}""").id)
    }
}

class TailBufferTest {
    @Test fun keepsEverythingUnderTheCap() {
        val t = TailBuffer(100); t.write("hello\n".toByteArray())
        assertEquals("hello\n", String(t.bytes()))
    }

    @Test fun keepsOnlyTheLastBytesAtALineStart() {
        val t = TailBuffer(20)
        for (i in 1..50) t.write("line $i\n".toByteArray())
        val s = String(t.bytes())
        assertTrue(s.length <= 20)
        assertTrue(s.endsWith("line 50\n"))
        assertTrue(s.startsWith("line "))
    }

    @Test fun oneHugeWriteIsTrimmed() {
        val t = TailBuffer(10); t.write(ByteArray(1000) { 'x'.code.toByte() })
        assertEquals(10, t.bytes().size)
    }
}

class UrlKindAndCrashTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun urlKinds() {
        assertEquals("tailnet", UrlKind.label("http://100.101.102.103:8765"))
        assertEquals("tailnet", UrlKind.label("http://rxmoi:8765"))
        assertEquals("tailnet", UrlKind.label("http://rxmoi.tail1234.ts.net:8765"))
        assertEquals("LAN", UrlKind.label("http://192.168.0.247:8765"))
        assertEquals("tunnel", UrlKind.label("https://abc.trycloudflare.com"))
        assertEquals("unknown", UrlKind.label("???"))
    }

    @Test fun crashStoreRoundTrip() {
        val s = CrashStore(tmp.root.resolve("crash"))
        assertNull(s.pending())
        s.write("main", IllegalStateException("boom"), 0, "abc1234")
        val text = s.pending()
        assertNotNull(text)
        assertTrue(text!!.contains("commit abc1234"))
        assertTrue(text.contains("java.lang.IllegalStateException: boom"))
        s.clear()
        assertNull(s.pending())
    }
}
