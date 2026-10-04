package com.example.arruler.beam

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
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

class FakeClock(var mono: Long = 1000, var wall: Long = 1_700_000_000_000) : BeamClock {
    override fun monoMs() = mono
    override fun wallMs() = wall
    fun advance(ms: Long) { mono += ms; wall += ms }
}

class TimelineTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun linesCarryBothClocksTypeAndData() {
        val c = FakeClock()
        val f = File(tmp.root, "t.jsonl")
        val t = Timeline(f, c)
        assertTrue(t.event(EventTypes.TAP, mapOf("x" to 10.5, "y" to 20, "view_w" to 1080, "view_h" to 2400, "target" to "box")))
        c.advance(250)
        t.event(EventTypes.MODE_CHANGE, mapOf("mode" to "SCAN"))
        t.close()
        val lines = f.readLines()
        assertEquals(2, lines.size)
        val o = Json.parseToJsonElement(lines[0]).jsonObject
        assertEquals("tap", o["type"]!!.jsonPrimitive.content)
        assertEquals(1000L, o["t_mono_ms"]!!.jsonPrimitive.content.toLong())
        assertEquals(1_700_000_000_000L, o["t_wall_ms"]!!.jsonPrimitive.content.toLong())
        assertEquals("box", o["data"]!!.jsonObject["target"]!!.jsonPrimitive.content)
        val o2 = Json.parseToJsonElement(lines[1]).jsonObject
        assertEquals(1250L, o2["t_mono_ms"]!!.jsonPrimitive.content.toLong())
        assertEquals(2L, o2["seq"]!!.jsonPrimitive.content.toLong())
    }

    @Test fun memoryIsBoundedButTheFileKeepsEverything() {
        val f = File(tmp.root, "t.jsonl")
        val t = Timeline(f, FakeClock(), memoryCap = 5)
        repeat(20) { t.event("e", mapOf("i" to it)) }
        t.close()
        assertEquals(5, t.snapshot().size)
        assertEquals(15L, t.snapshot().first().seq - 1)
        assertEquals(20L, t.total)
        assertEquals(20, f.readLines().size)
        assertEquals(20, t.countsByType()["e"])
    }

    @Test fun fileCapStopsWritingAndLeavesATruncationMarker() {
        val f = File(tmp.root, "t.jsonl")
        val t = Timeline(f, FakeClock(), fileCapBytes = 600)
        repeat(50) { t.event("e", mapOf("pad" to "x".repeat(40))) }
        t.close()
        val lines = f.readLines()
        assertTrue(lines.size < 50)
        assertTrue(lines.last().contains(EventTypes.TRUNCATED))
        assertEquals(50L, t.total)
        assertTrue(f.length() < 1200)
    }

    @Test fun neverThrowsAfterCloseOrOnBadValues() {
        val t = Timeline(File(tmp.root, "x/y/t.jsonl"), FakeClock())
        val weird = mapOf<String, Any?>("nan" to Double.NaN, "inf" to Float.POSITIVE_INFINITY, "obj" to Any(), "arr" to intArrayOf(1, 2), "n" to null, "ex" to IllegalStateException("boom"))
        assertTrue(t.event("w", weird))
        t.close()
        assertFalse(t.event("late"))
        assertEquals(1L, t.dropped)
        val line = t.snapshot().single().json
        val d = Json.parseToJsonElement(line).jsonObject["data"]!!.jsonObject
        assertEquals("null", d["nan"]!!.toString())
        assertEquals(2, d["arr"]!!.jsonArray.size)
        assertTrue(d["ex"]!!.jsonObject["stack"]!!.jsonPrimitive.content.contains("IllegalStateException"))
    }

    @Test fun unwritableFileOnlyCountsDrops() {
        val blocker = tmp.newFile("blocker")
        val t = Timeline(File(blocker, "sub/t.jsonl"), FakeClock())
        assertTrue(t.event("a"))
        assertEquals(1, t.snapshot().size)
        assertTrue(t.dropped >= 1)
    }

    @Test fun longStringsAndDepthAreClipped() {
        val s = EventJson.value("a".repeat(10_000)).jsonPrimitive.content
        assertTrue(s.length < 4100 && s.contains("chars]"))
        var m: Any? = "leaf"
        repeat(10) { m = mapOf("k" to m) }
        assertNotNull(EventJson.value(m))
    }

    @Test fun concurrentWritersKeepWholeLines() {
        val f = File(tmp.root, "t.jsonl")
        val t = Timeline(f, FakeClock(), memoryCap = 10)
        val ths = (0 until 4).map { k -> Thread { repeat(250) { t.event("e", mapOf("k" to k, "i" to it)) } } }
        ths.forEach { it.start() }; ths.forEach { it.join() }
        t.close()
        val lines = f.readLines()
        assertEquals(1000, lines.size)
        lines.forEach { Json.parseToJsonElement(it) }
        assertEquals((1L..1000L).toList(), lines.map { Json.parseToJsonElement(it).jsonObject["seq"]!!.jsonPrimitive.content.toLong() }.sorted())
    }
}

class DebounceAndFpsTest {
    @Test fun hitQualityOnlyOnChange() {
        val d = HitQualityDebouncer()
        assertTrue(d.shouldEmit("GOOD", "PLANE"))
        assertFalse(d.shouldEmit("GOOD", "PLANE"))
        assertTrue(d.shouldEmit("POOR", "PLANE"))
        assertTrue(d.shouldEmit("POOR", "DEPTH"))
        assertFalse(d.shouldEmit("POOR", "DEPTH"))
        d.reset()
        assertTrue(d.shouldEmit("POOR", "DEPTH"))
    }

    @Test fun fpsSummaryOncePerSecond() {
        val a = FpsAggregator()
        var out: Map<String, Any?>? = null
        var emitted = 0
        // 30 fps for 3.1 s with one 100 ms hiccup
        var t = 0L
        repeat(93) { i ->
            t += if (i == 40) 100 else 33
            val r = a.onFrame(t)
            if (r != null) { emitted++; out = r }
        }
        assertEquals(3, emitted)
        val fps = out!!["fps"] as Double
        assertTrue("fps $fps", fps in 25.0..31.0)
        assertNull(a.onFrame(t + 1))
    }
}
