package com.omarea.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Game preload policy: only for apps that own a mode, only while the engine
 * could act.
 */
class GamePreloadPolicyTest {

    private fun shouldPreload(
        enabled: Boolean = true,
        appModeActive: Boolean = true,
        engineOff: Boolean = false,
        trueOff: Boolean = false,
        rootAvailable: Boolean = true
    ) = GamePreloadPolicy.shouldPreload(enabled, appModeActive, engineOff, trueOff, rootAvailable)

    @Test
    fun `preloads only when everything is in place`() {
        assertTrue(shouldPreload())
        assertFalse(shouldPreload(enabled = false))
        assertFalse(shouldPreload(appModeActive = false))
        assertFalse(shouldPreload(engineOff = true))
        assertFalse(shouldPreload(trueOff = true))
        assertFalse(shouldPreload(rootAvailable = false))
    }
}
