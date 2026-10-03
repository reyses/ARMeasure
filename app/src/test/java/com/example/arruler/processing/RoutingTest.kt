package com.example.arruler.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRulesTest {
    private val gib = ProfileRules.GIB
    private fun sig(mem: Double, cores: Int = 8, pc: Int = 0, low: Boolean = false, bench: Long? = null) =
        DeviceSignals(pc, (mem * gib).toLong(), low, cores, benchMs = bench)

    @Test fun lowRamFlagIsLow() = assertEquals(Tier.LOW, ProfileRules.tier(sig(8.0, low = true)))
    @Test fun fewCoresIsLow() = assertEquals(Tier.LOW, ProfileRules.tier(sig(8.0, cores = 2)))
    @Test fun under3GibIsLow() = assertEquals(Tier.LOW, ProfileRules.tier(sig(2.8)))
    @Test fun fourGbPhoneIsLow() = assertEquals(Tier.LOW, ProfileRules.tier(sig(3.7)))
    @Test fun sixGbPhoneIsMid() = assertEquals(Tier.MID, ProfileRules.tier(sig(5.5)))
    @Test fun eightGbPhoneIsHigh() = assertEquals(Tier.HIGH, ProfileRules.tier(sig(7.4)))
    @Test fun perfClass31LiftsToMid() = assertEquals(Tier.MID, ProfileRules.tier(sig(4.0, pc = 31)))
    @Test fun perfClass33LiftsToHigh() = assertEquals(Tier.HIGH, ProfileRules.tier(sig(5.5, pc = 33)))
    @Test fun slowBenchmarkCapsToLow() = assertEquals(Tier.LOW, ProfileRules.tier(sig(11.0, bench = 2000)))
    @Test fun mediumBenchmarkCapsToMid() = assertEquals(Tier.MID, ProfileRules.tier(sig(11.0, bench = 900)))
    @Test fun fastBenchmarkKeepsHigh() = assertEquals(Tier.HIGH, ProfileRules.tier(sig(11.0, bench = 300)))
    @Test fun lowRamIgnoresPerfClass() = assertEquals(Tier.LOW, ProfileRules.tier(sig(8.0, pc = 34, low = true)))

    @Test fun batteryLowRules() {
        assertTrue(ProfileRules.isBatteryLow(10, false))
        assertFalse(ProfileRules.isBatteryLow(10, true))
        assertFalse(ProfileRules.isBatteryLow(50, false))
        assertFalse(ProfileRules.isBatteryLow(-1, false))
    }

    @Test fun benchmarkWorkloadFindsPlanesDeterministically() {
        val a = ProfileBenchmark.run(30_000)
        val b = ProfileBenchmark.run(30_000)
        assertEquals(a.voxels, b.voxels)
        assertEquals(a.planes, b.planes)
        assertTrue("planes=${a.planes}", a.planes >= 2)
    }
}

class RouterTest {
    private fun d(
        job: JobType = JobType.SCAN_ANALYZE, q: ObjectQuality? = null, pts: Int = 10_000, imgs: Int = 0,
        tier: Tier = Tier.MID, pc: Boolean = true, pref: UserPref = UserPref.AUTO,
        wifi: Boolean = true, bat: Boolean = false, thermal: Int = 0
    ) = Router.decide(job, q, JobEstimate(pts, imgs), tier, pc, pref, wifi, bat, thermal)

    @Test fun photogrammetryAlwaysPc() {
        for (p in UserPref.entries) for (t in Tier.entries) {
            val r = d(JobType.PHOTOGRAMMETRY, ObjectQuality.DETAILED, imgs = 60, tier = t, pref = p)
            assertEquals(Backend.PC, r.backend); assertFalse(r.blocked)
        }
    }

    @Test fun photogrammetryWithoutPcIsBlocked() {
        val r = d(JobType.PHOTOGRAMMETRY, ObjectQuality.DETAILED, imgs = 60, pc = false)
        assertTrue(r.blocked); assertEquals(Backend.PC, r.backend)
    }

    @Test fun lowTierOver50kGoesToPc() {
        val r = d(pts = 60_000, tier = Tier.LOW)
        assertEquals(Backend.PC, r.backend)
        assertEquals("PC: 60k points on a LOW phone", r.reason)
    }

    @Test fun midReasonMatchesBrief() = assertEquals("PC: 180k points on a MID phone", d(pts = 180_000, tier = Tier.MID).reason)

    @Test fun lowTierUnder50kStaysOnPhone() = assertEquals(Backend.PHONE, d(pts = 40_000, tier = Tier.LOW).backend)

    @Test fun comfortLimitsPerTier() {
        assertEquals(Backend.PHONE, d(pts = 150_000, tier = Tier.MID).backend)
        assertEquals(Backend.PC, d(pts = 150_001, tier = Tier.MID).backend)
        assertEquals(Backend.PHONE, d(pts = 400_000, tier = Tier.HIGH).backend)
        assertEquals(Backend.PC, d(pts = 400_001, tier = Tier.HIGH).backend)
    }

    @Test fun hotPhoneGoesToPc() {
        assertEquals(Backend.PC, d(thermal = 2).backend)
        assertEquals(Backend.PHONE, d(thermal = 1).backend)
    }

    @Test fun lowBatteryGoesToPc() = assertEquals(Backend.PC, d(bat = true).backend)

    @Test fun noPcStaysOnPhoneWithWarningWhenAboveComfort() {
        val r = d(pts = 200_000, tier = Tier.MID, pc = false)
        assertEquals(Backend.PHONE, r.backend)
        assertNotNull(r.warning)
        assertTrue(r.warning!!.contains("200k"))
    }

    @Test fun noPcNoWarningWhenComfortable() {
        val r = d(pc = false)
        assertEquals(Backend.PHONE, r.backend); assertNull(r.warning)
    }

    @Test fun prefPhoneForcesPhone() = assertEquals(Backend.PHONE, d(pts = 500_000, pref = UserPref.PHONE).backend)

    @Test fun prefPcWithoutPcFallsBackWithWarning() {
        val r = d(pref = UserPref.PC, pc = false)
        assertEquals(Backend.PHONE, r.backend); assertNotNull(r.warning)
    }

    @Test fun prefPcUsesPc() = assertEquals(Backend.PC, d(pref = UserPref.PC).backend)

    @Test fun mobileDataBigUploadAsksConfirmation() {
        val r = d(pts = 2_000_000, tier = Tier.HIGH, wifi = false)
        assertEquals(Backend.PC, r.backend); assertTrue(r.needsConfirmation)
    }

    @Test fun mobileDataSmallUploadNoConfirmation() {
        val r = d(pts = 200_000, tier = Tier.MID, wifi = false) // 3 MB
        assertEquals(Backend.PC, r.backend); assertFalse(r.needsConfirmation)
    }

    @Test fun wifiNeverAsksConfirmation() = assertFalse(d(pts = 2_000_000, tier = Tier.HIGH, wifi = true).needsConfirmation)

    @Test fun photosOnMobileDataAskConfirmation() =
        assertTrue(d(JobType.PHOTOGRAMMETRY, ObjectQuality.DETAILED, imgs = 60, wifi = false).needsConfirmation)

    @Test fun fineOnLowIsPcOnly() {
        val r = d(JobType.OBJECT_MESH, ObjectQuality.FINE, tier = Tier.LOW, pref = UserPref.PHONE)
        assertEquals(Backend.PC, r.backend)
        assertTrue(d(JobType.OBJECT_MESH, ObjectQuality.FINE, tier = Tier.LOW, pc = false).blocked)
    }

    @Test fun fineOnMidRunsOnPhone() = assertEquals(Backend.PHONE, d(JobType.OBJECT_MESH, ObjectQuality.FINE, tier = Tier.MID).backend)

    @Test fun quickRunsOnPhoneOnAnyTier() {
        for (t in Tier.entries) assertEquals(Backend.PHONE, d(JobType.OBJECT_MESH, ObjectQuality.QUICK, tier = t).backend)
    }

    @Test fun splatIsDisabled() = assertTrue(d(JobType.PHOTOGRAMMETRY, ObjectQuality.DETAILED_SPLAT, imgs = 60).blocked)

    @Test fun formatCount() {
        assertEquals("950", Router.formatCount(950)); assertEquals("180k", Router.formatCount(180_000))
    }

    // ---- availableQualities matrix: 3 tiers x PC on/off

    private fun opts(t: Tier, pc: Boolean, wifi: Boolean = true) = Router.availableQualities(t, pc, wifi).associateBy { it.quality }

    @Test fun matrixQuickAlwaysPhoneEnabled() {
        for (t in Tier.entries) for (pc in listOf(true, false)) {
            val o = opts(t, pc)[ObjectQuality.QUICK]!!
            assertEquals(Backend.PHONE, o.backend); assertTrue(o.enabled); assertNull(o.reason)
        }
    }

    @Test fun matrixFine() {
        for (pc in listOf(true, false)) {
            for (t in listOf(Tier.MID, Tier.HIGH)) {
                val o = opts(t, pc)[ObjectQuality.FINE]!!
                assertEquals(Backend.PHONE, o.backend); assertTrue(o.enabled)
            }
            val low = opts(Tier.LOW, pc)[ObjectQuality.FINE]!!
            assertEquals(Backend.PC, low.backend); assertEquals(pc, low.enabled)
            assertEquals(if (pc) null else "needs your PC", low.reason)
        }
    }

    @Test fun matrixDetailedNeedsPc() {
        for (t in Tier.entries) {
            val on = opts(t, true)[ObjectQuality.DETAILED]!!
            assertEquals(Backend.PC, on.backend); assertTrue(on.enabled)
            val off = opts(t, false)[ObjectQuality.DETAILED]!!
            assertFalse(off.enabled); assertEquals("needs your PC", off.reason)
        }
    }

    @Test fun matrixSplatAlwaysDisabled() {
        for (t in Tier.entries) for (pc in listOf(true, false)) {
            val o = opts(t, pc)[ObjectQuality.DETAILED_SPLAT]!!
            assertFalse(o.enabled); assertEquals(Backend.PC, o.backend); assertNotNull(o.reason)
        }
    }

    @Test fun matrixDefaults() {
        assertEquals(ObjectQuality.DETAILED, Router.defaultQuality(Router.availableQualities(Tier.HIGH, true)))
        assertEquals(ObjectQuality.DETAILED, Router.defaultQuality(Router.availableQualities(Tier.LOW, true)))
        assertEquals(ObjectQuality.FINE, Router.defaultQuality(Router.availableQualities(Tier.MID, false)))
        assertEquals(ObjectQuality.FINE, Router.defaultQuality(Router.availableQualities(Tier.HIGH, false)))
        assertEquals(ObjectQuality.QUICK, Router.defaultQuality(Router.availableQualities(Tier.LOW, false)))
    }

    @Test fun defaultAvoidsConfirmationOnMobileData() {
        // 60 photos = 120 MB over mobile data needs confirmation, so DETAILED is skipped.
        val o = Router.availableQualities(Tier.HIGH, true, onWifi = false)
        assertTrue(o.first { it.quality == ObjectQuality.DETAILED }.needsConfirmation)
        assertEquals(ObjectQuality.FINE, Router.defaultQuality(o))
    }

    @Test fun optionsCarryTimeAndAccuracy() {
        val o = opts(Tier.MID, true)
        assertEquals("±1 cm", o[ObjectQuality.QUICK]!!.accuracy)
        assertEquals("±5-8 mm", o[ObjectQuality.FINE]!!.accuracy)
        assertEquals("±1-2 mm", o[ObjectQuality.DETAILED]!!.accuracy)
        assertTrue(o.values.all { it.estimatedTime.isNotEmpty() && it.label.isNotEmpty() })
        assertEquals(4, o.size)
    }

    @Test fun qualityVoxelSizes() {
        assertEquals(5, ObjectQuality.QUICK.voxelMm); assertEquals(3, ObjectQuality.FINE.voxelMm)
        assertNull(ObjectQuality.DETAILED.voxelMm)
    }
}
