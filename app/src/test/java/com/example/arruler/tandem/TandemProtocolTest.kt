package com.example.arruler.tandem

import com.example.arruler.processing.BoxSpec
import com.example.arruler.processing.SupportPlaneSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class TandemProtocolTest {

    private val samples: List<TandemMessage> = listOf(
        TandemMessage.Hello("Pixel 9 Pro", "HIGH", true, "1.0.0", 420),
        TandemMessage.Hello("old", "LOW", false, "0.9", null),
        TandemMessage.Role(TandemRole.LEADER),
        TandemMessage.Role(TandemRole.HELPER),
        TandemMessage.ClockPing(3, 123456789L),
        TandemMessage.ClockPong(3, 123456789L, 5_000_000_000L, 5_000_100_000L),
        TandemMessage.ClockSet(-1_234_567_890L, 3_000_000L),
        TandemMessage.StartCapture(
            9_999_999_999L, CaptureMode.SPIN, 3, BoxSpec(listOf(1f, 0.9f, -0.5f), listOf(0.3f, 0.2f, 0.3f), 15f),
            SupportPlaneSpec(listOf(0f, 1f, 0f), -0.8f), listOf(1f, 0.8f, -0.5f), 12.5f,
        ),
        TandemMessage.StartCapture(1L, CaptureMode.WALK, 5),
        TandemMessage.StopCapture(42L),
        TandemMessage.Task("t1", TaskKind.MC_SLAB, "slab-1.bin", mapOf("a" to "b")),
        TandemMessage.Task("t2", TaskKind.PARTIAL_OBJECT),
        TandemMessage.TaskProgress("t1", 0.5f, "meshing"),
        TandemMessage.TaskResult("t1", true, "piece-1.bin", null, 1234, listOf("n1", "n2")),
        TandemMessage.TaskResult("t2", false, "", "boom", 5),
        TandemMessage.Status(3, 0.95f, 17, false, 0.6f),
        TandemMessage.Status(),
        TandemMessage.Complete("j1", true, mapOf("volume_m3" to "0.008")),
        TandemMessage.Error("helper", "disk full"),
        TandemMessage.Bye("done"),
    )

    @Test fun everyMessageRoundTrips() {
        for ((i, m) in samples.withIndex()) {
            val d = TandemCodec.decode(TandemCodec.encode(m, i.toLong()))
            assertTrue("decode of $m", d is Decoded.Ok)
            d as Decoded.Ok
            assertEquals(m, d.msg)
            assertEquals(i.toLong(), d.seq)
        }
    }

    @Test fun wireNamesAreStable() {
        val s = String(TandemCodec.encode(TandemMessage.ClockPing(1, 2)), Charsets.UTF_8)
        assertTrue(s, s.contains("\"v\":1") && s.contains("\"t\":\"clock_ping\"") && s.contains("\"t0\":2"))
    }

    @Test fun newerVersionIsReportedNotParsed() {
        val future = """{"v":2,"seq":1,"msg":{"t":"quantum","x":1}}"""
        val d = TandemCodec.decode(future.toByteArray())
        assertEquals(Decoded.UnsupportedVersion(2), d)
    }

    @Test fun unknownFieldsAreIgnoredButUnknownTypesAreMalformed() {
        val extra = """{"v":1,"seq":1,"msg":{"t":"bye","reason":"x","future_field":5},"also":1}"""
        val ok = TandemCodec.decode(extra.toByteArray())
        assertTrue(ok is Decoded.Ok && (ok.msg as TandemMessage.Bye).reason == "x")
        val unknown = """{"v":1,"seq":1,"msg":{"t":"quantum"}}"""
        assertTrue(TandemCodec.decode(unknown.toByteArray()) is Decoded.Malformed)
    }

    @Test fun fuzzNeverThrows() {
        val rng = Random(11)
        val valid = samples.map { TandemCodec.encode(it, 7) }
        var ok = 0; var bad = 0
        repeat(6000) { n ->
            val b: ByteArray = when (n % 5) {
                0 -> ByteArray(rng.nextInt(200)).also { rng.nextBytes(it) }                            // noise
                1 -> valid[rng.nextInt(valid.size)].let { it.copyOf(rng.nextInt(it.size + 1)) }        // truncated
                2 -> valid[rng.nextInt(valid.size)].copyOf().also { if (it.isNotEmpty()) it[rng.nextInt(it.size)] = rng.nextInt(256).toByte() } // one flipped byte
                3 -> valid[rng.nextInt(valid.size)].let { it + ByteArray(rng.nextInt(8)) { 'x'.code.toByte() } } // trailing junk
                else -> "{".repeat(rng.nextInt(50)).toByteArray()
            }
            when (TandemCodec.decode(b)) { is Decoded.Ok -> ok++; else -> bad++ }
        }
        assertTrue("some mutations still decode ($ok) and most do not ($bad)", ok > 0 && bad > 3000)
    }

    // ------------------------------------------------------------------------------------------ clock

    /**
     * Two clocks with a true skew, one-way delays = [base] + exponential jitter with different means per direction (asymmetric),
     * a 1 ms processing time on the helper. Returns the estimator error in ms for [n] exchanges.
     */
    private fun simulateClock(seed: Long, n: Int, skewNs: Long, baseMs: Double, upJitterMs: Double, downJitterMs: Double): Double {
        val rng = Random(seed)
        val est = ClockEstimator(n)
        var leaderT = 1_000_000_000_000L
        fun exp(mean: Double) = -mean * Math.log(1.0 - rng.nextDouble())
        repeat(n) {
            val up = ((baseMs + exp(upJitterMs)) * 1e6).toLong()
            val down = ((baseMs + exp(downJitterMs)) * 1e6).toLong()
            val t0 = leaderT
            val t1 = t0 + up + skewNs          // helper clock at receive
            val t2 = t1 + 1_000_000L           // helper processing 1 ms
            val t3 = t2 - skewNs + down        // leader clock at receive
            est.add(ClockSample(t0, t1, t2, t3))
            leaderT = t3 + 50_000_000L
        }
        return abs(est.offsetNs!! - skewNs) / 1e6
    }

    @Test fun clockOffsetErrorBelowFiveMillisecondsUnderSkewAndJitter() {
        var worst = 0.0
        var sum = 0.0
        val trials = 400
        for (seed in 1L..trials) {
            val skew = (seed % 7 - 3) * 777_000_000L + seed * 12_345L            // up to +-2.3 s plus odd microseconds
            val e = simulateClock(seed, 16, skew, baseMs = 3.0, upJitterMs = 4.0, downJitterMs = 4.0)
            worst = maxOf(worst, e); sum += e
        }
        println("clock offset error over $trials trials of 16 pings: mean %.3f ms, worst %.3f ms".format(sum / trials, worst))
        assertTrue("worst error $worst ms", worst < 5.0)
    }

    @Test fun clockEstimatorBeatsAverageWhenJitterIsLopsided() {
        // downlink much slower than uplink: the mean of all samples is biased by ~ (down - up) / 2, the min-RTT sample is not
        val e = simulateClock(5, 32, 400_000_000L, baseMs = 2.0, upJitterMs = 0.5, downJitterMs = 12.0)
        assertTrue("error $e ms", e < 5.0)
    }

    @Test fun clockEstimatorKeepsOnlyTheLastN() {
        val est = ClockEstimator(3)
        for (rtt in longArrayOf(10, 9, 8, 100, 100, 100)) est.add(ClockSample(0, 0, 0, rtt))
        assertEquals(3, est.count)
        assertEquals(100L, est.bestRttNs)
    }

    // ------------------------------------------------------------------------------------------ permissions

    @Test fun permissionsPerApiLevel() {
        val api37 = PeerPermissions.required(37)
        assertTrue(api37.containsAll(listOf(PeerPermissions.BLUETOOTH_SCAN, PeerPermissions.BLUETOOTH_ADVERTISE, PeerPermissions.BLUETOOTH_CONNECT, PeerPermissions.NEARBY_WIFI_DEVICES, PeerPermissions.LOCAL_NETWORK)))
        assertTrue(PeerPermissions.FINE_LOCATION !in api37)
        val api34 = PeerPermissions.required(34)
        assertEquals(listOf(PeerPermissions.BLUETOOTH_SCAN, PeerPermissions.BLUETOOTH_ADVERTISE, PeerPermissions.BLUETOOTH_CONNECT, PeerPermissions.NEARBY_WIFI_DEVICES), api34)
        val api32 = PeerPermissions.required(32)
        assertEquals(listOf(PeerPermissions.BLUETOOTH_SCAN, PeerPermissions.BLUETOOTH_ADVERTISE, PeerPermissions.BLUETOOTH_CONNECT, PeerPermissions.FINE_LOCATION), api32)
        assertEquals(listOf(PeerPermissions.BLUETOOTH_SCAN, PeerPermissions.BLUETOOTH_ADVERTISE, PeerPermissions.BLUETOOTH_CONNECT, PeerPermissions.FINE_LOCATION), PeerPermissions.required(31))
        assertEquals(listOf(PeerPermissions.FINE_LOCATION), PeerPermissions.required(30))
        assertEquals(listOf(PeerPermissions.FINE_LOCATION), PeerPermissions.required(29))
        assertEquals(listOf(PeerPermissions.COARSE_LOCATION), PeerPermissions.required(28))
        assertEquals(listOf(PeerPermissions.COARSE_LOCATION), PeerPermissions.required(24))
        assertEquals(listOf(PeerPermissions.NEARBY_WIFI_DEVICES), PeerPermissions.missing(34, setOf(PeerPermissions.BLUETOOTH_SCAN, PeerPermissions.BLUETOOTH_ADVERTISE, PeerPermissions.BLUETOOTH_CONNECT)))
        assertTrue(PeerPermissions.missing(34, PeerPermissions.required(34).toSet()).isEmpty())
    }
}
