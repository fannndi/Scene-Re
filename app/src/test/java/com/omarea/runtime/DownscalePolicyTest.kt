package com.omarea.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Downscale policy: only apply stored ratios while the engine could act.
 */
class DownscalePolicyTest {

    private fun shouldApply(
        hasRatio: Boolean = true,
        engineOff: Boolean = false,
        trueOff: Boolean = false,
        rootAvailable: Boolean = true
    ) = DownscalePolicy.shouldApply(hasRatio, engineOff, trueOff, rootAvailable)

    @Test
    fun `applies only when everything is in place`() {
        assertTrue(shouldApply())
        assertFalse(shouldApply(hasRatio = false))
        assertFalse(shouldApply(engineOff = true))
        assertFalse(shouldApply(trueOff = true))
        assertFalse(shouldApply(rootAvailable = false))
    }
}
