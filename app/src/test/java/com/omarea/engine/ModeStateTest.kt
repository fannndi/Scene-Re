package com.omarea.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ModeStateTest {

    @Test
    fun `first non-empty store wins in runtime-prop-saved order`() {
        assertEquals("performance", ModeState.resolve("performance", "powersave", "balance"))
        assertEquals("powersave", ModeState.resolve("", "powersave", "balance"))
        assertEquals("balance", ModeState.resolve("", "", "balance"))
        assertEquals("", ModeState.resolve("", "", ""))
    }

    @Test
    fun `legacy fast id is preserved verbatim for the UI`() {
        // The UI compares raw runtime ids; JSON lookups canonicalize elsewhere.
        assertEquals("fast", ModeState.resolve("", "", "fast"))
        assertEquals("custom", ModeState.resolve("custom", "", "fast"))
    }

    @Test
    fun `engine off wins over the remembered mode`() {
        val name = ModeState.displayName("powersave", engineOff = true) { "Power Save" }
        assertEquals("Profiles OFF (stock)", name)
    }

    @Test
    fun `empty mode is the global default`() {
        val name = ModeState.displayName("", engineOff = false) { "Power Save" }
        assertEquals("Global Default", name)
    }

    @Test
    fun `known mode is mapped through the caller name lookup`() {
        val name = ModeState.displayName("powersave", engineOff = false) { "Power Save" }
        assertEquals("Power Save", name)
    }
}
