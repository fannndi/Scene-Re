package com.omarea.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure SF frame-pacing math (AZenith ratio table on a 1e9/hz frame).
 */
class SfFramePacingTest {

    @Test
    fun `120hz shares the frame like AZenith`() {
        val p = SfFramePacing.pacingFor(120)
        val frame = 1_000_000_000.0 / 120
        assertEquals((frame * 0.58).toLong(), p.appDuration)
        assertEquals((frame * 0.32).toLong(), p.sfDuration)
        assertEquals(-(frame * 0.68).toLong(), p.appOffset)
        assertEquals(-(frame * 0.85).toLong(), p.sfOffset)
        assertEquals((frame * 0.28).toLong(), p.threshold)
    }

    @Test
    fun `60hz uses the low-rate ratios and clamps the threshold`() {
        val p = SfFramePacing.pacingFor(60)
        val frame = 1_000_000_000.0 / 60
        assertEquals((frame * 0.65).toLong(), p.appDuration)
        assertEquals((frame * 0.25).toLong(), p.sfDuration)
        assertEquals((frame * 0.38).toLong(), p.threshold)
    }

    @Test
    fun `out-of-range refresh rates are clamped and sane`() {
        val low = SfFramePacing.pacingFor(10)
        val high = SfFramePacing.pacingFor(999)
        assertTrue(low.appDuration > 0)
        assertTrue(high.appDuration > 0)
        assertTrue(low.threshold >= (1_000_000_000.0 / 30 * 0.22).toLong())
        assertTrue(high.threshold <= (1_000_000_000.0 / 240 * 0.45).toLong() + 1)
    }

    @Test
    fun `every owned prop is deleted on restore`() {
        assertEquals(15, SfFramePacing.PROPS.size)
        assertTrue(SfFramePacing.PROPS.all { it.startsWith("debug.sf.") })
    }
}
