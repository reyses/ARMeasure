package com.example.arruler.ar

import org.junit.Assert.assertEquals
import org.junit.Test

class GlGuardTest {
    @Test fun capturesTheFirstContextThenRestoresWhenAnotherTookOver() {
        assertEquals(GlAction.NONE, GlGuard.action(captured = false, hasCurrent = false, currentIsCaptured = false))
        assertEquals(GlAction.CAPTURE, GlGuard.action(captured = false, hasCurrent = true, currentIsCaptured = false))
        assertEquals(GlAction.NONE, GlGuard.action(captured = true, hasCurrent = true, currentIsCaptured = true))
        assertEquals(GlAction.RESTORE, GlGuard.action(captured = true, hasCurrent = true, currentIsCaptured = false))
        assertEquals(GlAction.RESTORE, GlGuard.action(captured = true, hasCurrent = false, currentIsCaptured = false))
    }
}
