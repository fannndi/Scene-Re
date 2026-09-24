package com.omarea.library.calculator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Flags] backs the accessibility-service info flags in
 * `AccessibilityScenceMode`. `removeFlag` is the interesting one: an inverted
 * mask is easy to get wrong, and getting it wrong would leave a flag enabled
 * when the code believes it was cleared.
 */
class FlagsTest {

    @Test
    fun `addFlag sets a single bit`() {
        val flags = Flags(0b0000)
        assertEquals(0b0001, flags.addFlag(0b0001))
    }

    @Test
    fun `addFlag is idempotent`() {
        val flags = Flags(0b0001)
        assertEquals(0b0001, flags.addFlag(0b0001))
    }

    @Test
    fun `addFlag accumulates distinct bits`() {
        val flags = Flags(0)
        flags.addFlag(0b0001)
        flags.addFlag(0b0100)
        // 0b0001 | 0b0100 | 0b1000 == 0b1101 == 13
        assertEquals(0b1101, flags.addFlag(0b1000))
    }

    @Test
    fun `removeFlag clears a set bit`() {
        val flags = Flags(0b0101)
        assertEquals(0b0001, flags.removeFlag(0b0100))
    }

    @Test
    fun `removeFlag on an unset bit is a no-op`() {
        val flags = Flags(0b0001)
        assertEquals(0b0001, flags.removeFlag(0b0100))
    }

    @Test
    fun `removeFlag does not disturb neighbouring bits`() {
        // The inverted-mask bug would show up here: clearing bit 1 must leave
        // bits 0 and 2 alone.
        val flags = Flags(0b1111)
        assertEquals(0b1101, flags.removeFlag(0b0010))
    }

    @Test
    fun `add then remove returns to the original value`() {
        val original = 0b1010
        val flags = Flags(original)
        flags.addFlag(0b0101)
        assertEquals(original, flags.removeFlag(0b0101))
    }

    @Test
    fun `works with the real accessibility service info flag values`() {
        // FLAG_REPORT_VIEW_IDS = 16, FLAG_RETRIEVE_INTERACTIVE_WINDOWS = 32,
        // FLAG_INCLUDE_NOT_IMPORTANT_VIEWS = 8 (as used by the service).
        val flags = Flags(0)
        flags.addFlag(8)
        flags.addFlag(16)
        // 8 | 16 | 32 == 56
        assertEquals(56, flags.addFlag(32))
        // Clearing 8 from 56 leaves 16 | 32 == 48
        assertEquals(48, flags.removeFlag(8))
        assertTrue((flags.removeFlag(8) and 8) == 0)
    }
}
