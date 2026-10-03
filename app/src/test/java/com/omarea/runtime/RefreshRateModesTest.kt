package com.omarea.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Refresh-rate picker source contract: the per-app picker lists modes from
 * `dumpsys display` (the kr-script display_modes.sh asset no longer ships),
 * dedupes repeated entries and orders them highest-Hz first.
 */
class RefreshRateModesTest {

    // Verbatim excerpt of `dumpsys display` on the target device (surya).
    private val deviceDump = """
        DisplayMode{id=0, width=1080, height=2400, xDpi=391.885, yDpi=395.844, refreshRate=120.00001, appVsyncOffsetNanos=2000000, presentationDeadlineNanos=8333333, group=0}
        DisplayMode{id=1, width=1080, height=2400, xDpi=391.885, yDpi=395.844, refreshRate=90.0, appVsyncOffsetNanos=2000000, presentationDeadlineNanos=11111111, group=0}
        DisplayMode{id=2, width=1080, height=2400, xDpi=391.885, yDpi=395.844, refreshRate=60.000004, appVsyncOffsetNanos=1000000, presentationDeadlineNanos=16666666, group=0}
        DisplayMode{id=3, width=1080, height=2400, xDpi=391.885, yDpi=395.844, refreshRate=50.0, appVsyncOffsetNanos=1000000, presentationDeadlineNanos=20000000, group=0}
        DisplayMode{id=4, width=1080, height=2400, xDpi=391.885, yDpi=395.844, refreshRate=30.000002, appVsyncOffsetNanos=1000000, presentationDeadlineNanos=33333333, group=0}
        DisplayMode{id=2, width=1080, height=2400, xDpi=391.885, yDpi=395.844, refreshRate=60.000004, appVsyncOffsetNanos=1000000, presentationDeadlineNanos=16666666, group=0}
    """.trimIndent()

    @Test
    fun `parses device modes, dedupes and sorts highest Hz first`() {
        val modes = RefreshRateController.parseModes(deviceDump)

        assertEquals(listOf(0, 1, 2, 3, 4), modes.map { it.id })
        assertEquals(listOf(120, 90, 60, 50, 30), modes.map { it.hz })
    }

    @Test
    fun `repeated mode ids collapse to one entry`() {
        assertEquals(5, RefreshRateController.parseModes(deviceDump).size)
    }

    @Test
    fun `unreadable or empty dumpsys yields no modes`() {
        assertEquals(emptyList<RefreshRateController.DisplayMode>(), RefreshRateController.parseModes(""))
        assertEquals(emptyList<RefreshRateController.DisplayMode>(), RefreshRateController.parseModes("no modes here"))
    }

    @Test
    fun `malformed refresh rate is skipped instead of crashing`() {
        val modes = RefreshRateController.parseModes(
            "DisplayMode{id=7, refreshRate=abc, group=0}\n" +
                "DisplayMode{id=8, refreshRate=144.00001, group=0}"
        )
        assertEquals(listOf(RefreshRateController.DisplayMode(8, 144)), modes)
    }
}
