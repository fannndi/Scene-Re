package com.omarea.util.measure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubsampleMathTest {

    @Test
    fun `spread of odd window is the middle value`() {
        val spread = SubsampleMath.spread(listOf(300.0, 100.0, 200.0))
        assertEquals(200.0, spread.median!!, 1e-9)
        assertEquals(100.0, spread.min!!, 1e-9)
        assertEquals(300.0, spread.max!!, 1e-9)
        assertEquals(200.0, spread.range!!, 1e-9)
    }

    @Test
    fun `spread of even window averages the middle pair`() {
        val spread = SubsampleMath.spread(listOf(10.0, 40.0, 20.0, 30.0))
        assertEquals(25.0, spread.median!!, 1e-9)
    }

    @Test
    fun `null readings are ignored`() {
        val spread = SubsampleMath.spread(listOf(null, 5.0, null, 15.0))
        assertEquals(10.0, spread.median!!, 1e-9)
        assertEquals(5.0, spread.min!!, 1e-9)
        assertEquals(15.0, spread.max!!, 1e-9)
    }

    @Test
    fun `all-null window stays null`() {
        val spread = SubsampleMath.spread(listOf(null, null))
        assertNull(spread.median)
        assertNull(spread.min)
        assertNull(spread.max)
        assertNull(spread.range)
    }

    @Test
    fun `spreadLong keeps integer precision`() {
        val spread = SubsampleMath.spreadLong(listOf(1804800L, 300000L, 1612800L))
        assertEquals(1612800.0, spread.median!!, 1e-9)
        assertEquals(300000.0, spread.min!!, 1e-9)
        assertEquals(1804800.0, spread.max!!, 1e-9)
        assertEquals(SubsampleMath.medianLong(listOf(1L, 2L, 3L)), 2L)
        assertNull(SubsampleMath.medianLong(emptyList()))
    }

    @Test
    fun `delta propagates nulls`() {
        assertEquals(5.0, SubsampleMath.delta(10.0, 5.0)!!, 1e-9)
        assertNull(SubsampleMath.delta(null, 5.0))
        assertNull(SubsampleMath.delta(5.0, null))
    }
}
