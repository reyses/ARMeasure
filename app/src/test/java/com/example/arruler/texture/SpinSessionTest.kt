package com.example.arruler.texture

import com.example.arruler.geometry.Vec3
import com.example.arruler.objscan.ObjectBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpinSessionTest {
    private val k = Intrinsics(500f, 500f, 320f, 240f, 640, 480)
    private val box = ObjectBox(Vec3(0f, 0f, 0f), 0f, 0.2f, 0.2f, 0.2f)
    private val pose = CameraPose.lookAt(Vec3(0.4f, 0.25f, 0f), Vec3(0f, 0.1f, 0f))
    private val cube = SyntheticCube(cells = 8)

    private var yaw = 0.0

    /** One image of the turning cube; the object turns 2 degrees per image. */
    private fun feed(s: SpinSession, p: CameraPose = pose): SpinVerdict? {
        val img = SyntheticCube.luma(cube.render(p, k, Math.toRadians(yaw).toFloat()))
        yaw += 2.0
        return s.onImage(true, p.m, k, img, k.width, 1, Sharpness.laplacianVariance(img, k.width, k.height, k.width))
    }

    private fun feedUntil(s: SpinSession, limit: Int = 400, stop: (SpinSession) -> Boolean) {
        var n = 0
        while (!stop(s) && n++ < limit) feed(s)
    }

    @Test fun aCapReachedTurnPausesBetweenTurnsAndTakesNoPhotos() {
        val s = SpinSession(box, SpinConfig(maxPerTurn = 6), turns = 2)
        feedUntil(s) { it.stage != SpinStage.SPINNING }
        assertEquals(SpinStage.BETWEEN, s.stage)
        assertEquals(6, s.keptInTurn)
        assertEquals(1, s.turn)
        assertNull(feed(s))                       // paused: not judged
        assertEquals(6, s.totalKept)
    }

    @Test fun theNextTurnStartsFreshAndTheLastTurnEndsDone() {
        val s = SpinSession(box, SpinConfig(maxPerTurn = 6), turns = 2)
        feedUntil(s) { it.stage == SpinStage.BETWEEN }
        assertTrue(s.startNextTurn())
        assertEquals(2, s.turn); assertEquals(0, s.keptInTurn); assertEquals(SpinStage.SPINNING, s.stage)
        assertEquals(SpinVerdict.KEEP, feed(s))   // the first image of a turn is always kept
        feedUntil(s) { it.stage != SpinStage.SPINNING }
        assertEquals(SpinStage.DONE, s.stage)
        assertEquals(12, s.totalKept)
        assertFalse(s.startNextTurn())
        assertNull(feed(s))
    }

    @Test fun aTurnCanBeEndedOnlyAfterEnoughPhotos() {
        val s = SpinSession(box, SpinConfig(maxPerTurn = 60), turns = 2)
        assertFalse(s.endTurn())
        feedUntil(s) { it.keptInTurn >= SpinSession.MIN_TO_END_TURN - 1 }
        assertFalse(s.canEndTurn)
        feedUntil(s) { it.keptInTurn >= SpinSession.MIN_TO_END_TURN }
        assertTrue(s.canEndTurn)
        assertTrue(s.endTurn())
        assertEquals(SpinStage.BETWEEN, s.stage)
        assertFalse(s.canEndTurn)
    }

    @Test fun theLastTurnCannotBeEndedEarly() {
        val s = SpinSession(box, SpinConfig(maxPerTurn = 60), turns = 1)
        feedUntil(s) { it.keptInTurn >= SpinSession.MIN_TO_END_TURN }
        assertFalse(s.canEndTurn)
        assertFalse(s.endTurn())
    }

    @Test fun aMovedPhoneTakesNoPhotosUntilItIsBack() {
        val s = SpinSession(box, SpinConfig(maxPerTurn = 60), turns = 2)
        assertEquals(SpinVerdict.KEEP, feed(s))
        val bumped = CameraPose.lookAt(Vec3(0.4f, 0.25f, 0.05f), Vec3(0f, 0.1f, 0f))   // 5 cm sideways
        val kept = s.keptInTurn
        assertNull(feed(s, bumped))
        assertTrue(s.phoneMoved)
        assertEquals(kept, s.keptInTurn)
        feed(s)                                   // back on the stand
        assertFalse(s.phoneMoved)
    }

    @Test fun notTrackingIsReportedAndNothingIsKept() {
        val s = SpinSession(box)
        val img = SyntheticCube.luma(cube.render(pose, k, 0f))
        assertEquals(SpinVerdict.NOT_TRACKING, s.onImage(false, pose.m, k, img, k.width, 1, 10.0))
        assertEquals(0, s.totalKept)
    }
}
