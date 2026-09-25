package com.omarea.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayModesTest {
    @Test
    fun parsesIdLabelLines() {
        val modes = DisplayModes.parseModes(
            """
            2| 120Hz
            1| 90Hz
            0| 60Hz
            """.trimIndent()
        )
        assertEquals(3, modes.size)
        assertEquals(DisplayModes.Mode(2, "120Hz"), modes[0])
        assertEquals(120, modes[0].hz)
        assertEquals(90, modes[1].hz)
        assertEquals(60, modes[2].hz)
    }

    @Test
    fun sortsByRefreshRateDescending() {
        val modes = DisplayModes.parseModes("0|60Hz\n3|120Hz\n1|90Hz")
        assertEquals(listOf(120, 90, 60), modes.map { it.hz })
    }

    @Test
    fun skipsMalformedLines() {
        val modes = DisplayModes.parseModes(
            "0|60Hz\ngarbage\n|\n7|\n5|120Hz"
        )
        assertEquals(2, modes.size)
        assertEquals(listOf(120, 60), modes.map { it.hz })
    }

    @Test
    fun labelWithoutNumberIsZeroHz() {
        val modes = DisplayModes.parseModes("1|auto")
        assertEquals(1, modes.size)
        assertEquals(0, modes[0].hz)
    }
}
