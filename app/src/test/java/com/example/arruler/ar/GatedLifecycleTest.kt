package com.example.arruler.ar

import androidx.lifecycle.Lifecycle.State
import org.junit.Assert.assertEquals
import org.junit.Test

class GatedLifecycleTest {
    @Test fun followsParentWhenNotPaused() {
        for (s in State.entries) assertEquals(s, gatedState(s, false))
    }

    @Test fun pausedHoldsAtCreatedButNeverRevivesOrKills() {
        assertEquals(State.CREATED, gatedState(State.RESUMED, true))
        assertEquals(State.CREATED, gatedState(State.STARTED, true))
        assertEquals(State.CREATED, gatedState(State.CREATED, true))
        assertEquals(State.INITIALIZED, gatedState(State.INITIALIZED, true))
        assertEquals(State.DESTROYED, gatedState(State.DESTROYED, true))
    }
}
