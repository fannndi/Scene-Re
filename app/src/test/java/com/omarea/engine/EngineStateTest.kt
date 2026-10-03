package com.omarea.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * State precedence contract: TRUE OFF > Monitor mode (no root) > engine
 * switch. Every surface derives its wording from this one resolver.
 */
class EngineStateTest {

    private fun resolve(root: Boolean = true, trueOff: Boolean = false, engineOff: Boolean = false) =
        EngineState.resolve(root, trueOff, engineOff)

    @Test
    fun `engine on tunes`() {
        assertEquals(EngineState.TUNING, resolve())
    }

    @Test
    fun `engine off is stock even with root`() {
        assertEquals(EngineState.STOCK, resolve(engineOff = true))
    }

    @Test
    fun `no root is monitor mode`() {
        assertEquals(EngineState.MONITOR, resolve(root = false))
    }

    @Test
    fun `true off wins over everything`() {
        assertEquals(EngineState.TRUE_OFF, resolve(trueOff = true))
        assertEquals(EngineState.TRUE_OFF, resolve(trueOff = true, engineOff = true))
        assertEquals(EngineState.TRUE_OFF, resolve(trueOff = true, root = false))
    }

    @Test
    fun `monitor mode wins over the engine switch`() {
        assertEquals(EngineState.MONITOR, resolve(root = false, engineOff = true))
    }
}
