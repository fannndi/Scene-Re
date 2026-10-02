package com.omarea.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Process-priority boost policy: only for apps that own a mode, only while
 * the engine could act.
 */
class ProcessPriorityPolicyTest {

    private fun shouldBoost(
        enabled: Boolean = true,
        appModeActive: Boolean = true,
        engineOff: Boolean = false,
        trueOff: Boolean = false,
        rootAvailable: Boolean = true
    ) = ProcessPriorityPolicy.shouldBoost(enabled, appModeActive, engineOff, trueOff, rootAvailable)

    @Test
    fun `boosts only when everything is in place`() {
        assertTrue(shouldBoost())
        assertFalse(shouldBoost(enabled = false))
        assertFalse(shouldBoost(appModeActive = false))
        assertFalse(shouldBoost(engineOff = true))
        assertFalse(shouldBoost(trueOff = true))
        assertFalse(shouldBoost(rootAvailable = false))
    }
}
