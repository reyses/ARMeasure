package com.example.arruler.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionErrorTest {
    @Test fun shortReasonIsOneLineWithTheRootCause() {
        assertEquals("IllegalStateException: boom", ActionErrors.shortReason(IllegalStateException("boom")))
        assertEquals("NoSuchElementException", ActionErrors.shortReason(NoSuchElementException()))
        val wrapped = RuntimeException("upload\nfailed", java.io.IOException("disk full"))
        assertEquals("RuntimeException: upload failed (IOException: disk full)", ActionErrors.shortReason(wrapped))
        val long = ActionErrors.shortReason(IllegalArgumentException("x".repeat(500)))
        assertEquals(ActionErrors.MAX_REASON, long.length)
        assertTrue(long.endsWith("..."))
    }

    @Test fun reportCarriesHeaderActionAndStack() {
        val e = IllegalStateException("bad buffer")
        val err = ActionErrors.of("View 3D", e, "ARMeasure 1.0 commit abc", 0L)
        assertEquals("View 3D", err.action)
        assertEquals("IllegalStateException: bad buffer", err.reason)
        assertTrue(err.report.startsWith("ARMeasure 1.0 commit abc\naction View 3D at 1970-01-01 00:00:00 UTC"))
        assertTrue(err.report.contains("ActionErrorTest.reportCarriesHeaderActionAndStack"))
    }
}
