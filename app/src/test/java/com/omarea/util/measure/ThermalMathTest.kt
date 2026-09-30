package com.omarea.util.measure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalMathTest {

    @Test
    fun `decodes millidegree readings`() {
        assertEquals(59.1, ThermalMath.toCelsius(59100.0), 0.001)
        assertEquals(-5.0, ThermalMath.toCelsius(-5000.0), 0.001)
    }

    @Test
    fun `decodes deci-degree readings`() {
        assertEquals(38.7, ThermalMath.toCelsius(387.0), 0.001)
    }

    @Test
    fun `keeps plain degree readings`() {
        assertEquals(42.0, ThermalMath.toCelsius(42.0), 0.001)
    }

    @Test
    fun `filters impossible values, level nodes are type-filtered`() {
        assertFalse(ThermalMath.isPlausible(180.0))
        assertFalse(ThermalMath.isPlausible(-60.0))
        assertTrue(ThermalMath.isPlausible(59.1))
        // 78 is ambiguous by value alone; level nodes (*-lvl*) are excluded by
        // the zone-type filter before decoding (documented limitation).
        assertEquals(78.0, ThermalMath.decodePlausible(78.0)!!, 0.001)
    }

    @Test
    fun `format renders one decimal or placeholder`() {
        assertEquals("59.1°C", ThermalMath.format(59.1))
        assertEquals("--", ThermalMath.format(null))
    }
}
