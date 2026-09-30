package com.omarea.util.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Polarity calibration must be deterministic: a community kernel that reports
 * charging current as negative must not flip the UI sign.
 */
class CurrentMathTest {

    @Test
    fun `calibration follows the observed polarity`() {
        // stock-ish: positive while charging
        assertEquals(true, CurrentMath.calibrate(600_000, isCharging = true, isDischarging = false, current = null))
        // community kernel: negative while charging
        assertEquals(false, CurrentMath.calibrate(-600_000, isCharging = true, isDischarging = false, current = null))
        // discharging with negative raw = standard polarity
        assertEquals(true, CurrentMath.calibrate(-400_000, isCharging = false, isDischarging = true, current = null))
        // discharging with positive raw = inverted polarity
        assertEquals(false, CurrentMath.calibrate(400_000, isCharging = false, isDischarging = true, current = null))
    }

    @Test
    fun `calibration keeps the previous value when undecidable`() {
        assertEquals(null, CurrentMath.calibrate(0, isCharging = true, isDischarging = false, current = null))
        assertEquals(true, CurrentMath.calibrate(0, isCharging = false, isDischarging = true, current = true))
        assertEquals(false, CurrentMath.calibrate(500, isCharging = false, isDischarging = false, current = false))
    }

    @Test
    fun `canonical flips only for inverted polarity`() {
        assertEquals(600L, CurrentMath.canonical(600L, true))
        assertEquals(-600L, CurrentMath.canonical(-600L, true))
        assertEquals(600L, CurrentMath.canonical(-600L, false))
        assertEquals(-600L, CurrentMath.canonical(600L, false))
        assertEquals(600L, CurrentMath.canonical(600L, null))
    }

    @Test
    fun `median handles odd and even windows`() {
        assertEquals(5L, CurrentMath.median(listOf(9L, 1L, 5L)))
        assertEquals(7L, CurrentMath.median(listOf(1L, 5L, 9L, 10L)))
        assertNull(CurrentMath.median(emptyList()))
    }
}
