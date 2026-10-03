package com.example.arruler.texture

import com.example.arruler.processing.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyframeBudgetTest {
    @Test fun budgetsGrowWithTheTier() {
        assertTrue(KeyframeBudget.totalPixels(Tier.LOW) < KeyframeBudget.totalPixels(Tier.MID))
        assertTrue(KeyframeBudget.totalPixels(Tier.MID) < KeyframeBudget.totalPixels(Tier.HIGH))
        // 120 photos at 1080p would be 249 M pixels (about 1 GB); the largest budget is 40 M pixels (160 MB)
        assertTrue(KeyframeBudget.totalPixels(Tier.HIGH) <= 40_000_000)
    }

    @Test fun eachPhotoGetsAShareAndNeverMoreThanItsOwnSize() {
        assertEquals(400_000, KeyframeBudget.perImage(24_000_000, 60, 1920 * 1080))
        assertEquals(307_200, KeyframeBudget.perImage(24_000_000, 10, 640 * 480))
        assertEquals(100_000, KeyframeBudget.perImage(1_000_000, 100, 1920 * 1080))     // floor
    }

    @Test fun targetSizeKeepsTheAspectAndFitsTheBudget() {
        assertEquals(1920 to 1080, KeyframeBudget.targetSize(1920, 1080, 3_000_000))
        val (w, h) = KeyframeBudget.targetSize(1920, 1080, 400_000)
        assertTrue("$w x $h", w * h <= 400_000 && w * h > 380_000)
        assertEquals(1920.0 / 1080.0, w.toDouble() / h, 0.02)
    }
}
