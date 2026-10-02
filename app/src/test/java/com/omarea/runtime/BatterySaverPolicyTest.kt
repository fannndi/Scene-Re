package com.omarea.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Battery-saver overlay policy contract (Notification v2 sibling): the
 * overlay only ever engages while the engine could write, and only once.
 */
class BatterySaverPolicyTest {

    private fun decide(
        engineOff: Boolean = false,
        trueOff: Boolean = false,
        rootAvailable: Boolean = true,
        enabled: Boolean = true,
        saverOn: Boolean = false,
        overlayActive: Boolean = false
    ) = BatterySaverPolicy.decide(engineOff, trueOff, rootAvailable, enabled, saverOn, overlayActive)

    @Test
    fun `saver on with no overlay applies powersave`() {
        assertEquals(BatterySaverPolicy.Action.APPLY_OVERLAY, decide(saverOn = true))
    }

    @Test
    fun `saver on with an active overlay is a no-op`() {
        assertEquals(BatterySaverPolicy.Action.NONE, decide(saverOn = true, overlayActive = true))
    }

    @Test
    fun `saver off with an active overlay restores the base`() {
        assertEquals(BatterySaverPolicy.Action.RESTORE_BASE, decide(saverOn = false, overlayActive = true))
    }

    @Test
    fun `saver off without an overlay is a no-op`() {
        assertEquals(BatterySaverPolicy.Action.NONE, decide())
    }

    @Test
    fun `engine off never writes`() {
        assertEquals(BatterySaverPolicy.Action.NONE, decide(engineOff = true, saverOn = true))
        assertEquals(
            BatterySaverPolicy.Action.NONE,
            decide(engineOff = true, saverOn = false, overlayActive = true)
        )
    }

    @Test
    fun `true off never writes`() {
        assertEquals(BatterySaverPolicy.Action.NONE, decide(trueOff = true, saverOn = true))
        assertEquals(
            BatterySaverPolicy.Action.NONE,
            decide(trueOff = true, saverOn = false, overlayActive = true)
        )
    }

    @Test
    fun `monitor mode never writes`() {
        assertEquals(BatterySaverPolicy.Action.NONE, decide(rootAvailable = false, saverOn = true))
        assertEquals(
            BatterySaverPolicy.Action.NONE,
            decide(rootAvailable = false, saverOn = false, overlayActive = true)
        )
    }

    @Test
    fun `disabled overlay never writes`() {
        assertEquals(BatterySaverPolicy.Action.NONE, decide(enabled = false, saverOn = true))
        assertEquals(
            BatterySaverPolicy.Action.NONE,
            decide(enabled = false, saverOn = false, overlayActive = true)
        )
    }
}
