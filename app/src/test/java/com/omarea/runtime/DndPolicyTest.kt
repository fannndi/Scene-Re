package com.omarea.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DND-while-app-mode policy contract: engage once for an explicit per-app
 * mode, always restore the previous filter, never engage while blocked.
 */
class DndPolicyTest {

    private fun decide(
        enabled: Boolean = true,
        granted: Boolean = true,
        blocked: Boolean = false,
        appModeActive: Boolean = false,
        dndActive: Boolean = false
    ) = DndPolicy.decide(enabled, granted, blocked, appModeActive, dndActive)

    @Test
    fun `explicit app mode engages DND once`() {
        assertEquals(DndPolicy.Action.ENTER, decide(appModeActive = true))
        assertEquals(DndPolicy.Action.NONE, decide(appModeActive = true, dndActive = true))
    }

    @Test
    fun `leaving the app restores the filter`() {
        assertEquals(DndPolicy.Action.EXIT, decide(appModeActive = false, dndActive = true))
        assertEquals(DndPolicy.Action.NONE, decide())
    }

    @Test
    fun `disabled or not granted restores an active DND`() {
        assertEquals(DndPolicy.Action.EXIT, decide(enabled = false, dndActive = true))
        assertEquals(DndPolicy.Action.EXIT, decide(granted = false, dndActive = true))
        assertEquals(DndPolicy.Action.NONE, decide(enabled = false, appModeActive = true))
        assertEquals(DndPolicy.Action.NONE, decide(granted = false, appModeActive = true))
    }

    @Test
    fun `blocked states never engage but always restore their own change`() {
        assertEquals(DndPolicy.Action.NONE, decide(blocked = true, appModeActive = true))
        // Restoring our own change is the unfreeze-style exception.
        assertEquals(DndPolicy.Action.EXIT, decide(blocked = true, dndActive = true))
    }
}
