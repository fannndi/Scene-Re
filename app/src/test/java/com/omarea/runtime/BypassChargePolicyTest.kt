package com.omarea.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Bypass-charging policy matrix: enable only above the threshold while the
 * engine may act; disable (heal) whenever the conditions stop holding.
 */
class BypassChargePolicyTest {

    private fun decide(
        enabled: Boolean = true,
        engineOff: Boolean = false,
        trueOff: Boolean = false,
        rootAvailable: Boolean = true,
        charging: Boolean = true,
        level: Int = 90,
        threshold: Int = 80,
        active: Boolean = false
    ) = BypassChargePolicy.decide(enabled, engineOff, trueOff, rootAvailable, charging, level, threshold, active)

    @Test
    fun `enables at or above the threshold while charging`() {
        assertEquals(BypassChargePolicy.Action.ENABLE, decide(level = 80))
        assertEquals(BypassChargePolicy.Action.ENABLE, decide(level = 90))
        assertEquals(BypassChargePolicy.Action.NONE, decide(level = 79))
    }

    @Test
    fun `never enables while blocked or unplugged`() {
        assertEquals(BypassChargePolicy.Action.NONE, decide(enabled = false))
        assertEquals(BypassChargePolicy.Action.NONE, decide(engineOff = true))
        assertEquals(BypassChargePolicy.Action.NONE, decide(trueOff = true))
        assertEquals(BypassChargePolicy.Action.NONE, decide(rootAvailable = false))
        assertEquals(BypassChargePolicy.Action.NONE, decide(charging = false))
    }

    @Test
    fun `disables when conditions stop holding`() {
        assertEquals(BypassChargePolicy.Action.DISABLE, decide(level = 50, active = true))
        assertEquals(BypassChargePolicy.Action.DISABLE, decide(charging = false, active = true))
        assertEquals(BypassChargePolicy.Action.DISABLE, decide(enabled = false, active = true))
        assertEquals(BypassChargePolicy.Action.DISABLE, decide(engineOff = true, active = true))
        assertEquals(BypassChargePolicy.Action.DISABLE, decide(trueOff = true, active = true))
    }

    @Test
    fun `heals without root too`() {
        // Root may be gone while the node is still paused and charging stopped:
        // the disable is still returned so the change heals as soon as a root
        // trigger runs again.
        assertEquals(
            BypassChargePolicy.Action.DISABLE,
            decide(rootAvailable = false, charging = false, active = true)
        )
    }

    @Test
    fun `stays put while active and still charging above the threshold`() {
        assertEquals(BypassChargePolicy.Action.NONE, decide(active = true))
    }
}
