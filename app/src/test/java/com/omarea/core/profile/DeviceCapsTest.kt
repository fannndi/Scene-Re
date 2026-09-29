package com.omarea.core.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapsTest {

    private val freqs = listOf(300000L, 576000L, 1017600L, 1804800L)

    @Test
    fun `clamps below the lowest OPP`() {
        assertEquals(300000L, DeviceCaps.clampFreq(5000, freqs))
    }

    @Test
    fun `keeps an exact OPP`() {
        assertEquals(1017600L, DeviceCaps.clampFreq(1017600, freqs))
    }

    @Test
    fun `clamps beyond the highest OPP`() {
        assertEquals(1804800L, DeviceCaps.clampFreq(2500000, freqs))
    }

    @Test
    fun `picks the nearest OPP`() {
        assertEquals(1017600L, DeviceCaps.clampFreq(1_000_000, freqs))
    }

    @Test
    fun `returns the request when no OPP list is available`() {
        assertEquals(4242L, DeviceCaps.clampFreq(4242, emptyList()))
    }

    @Test
    fun `governor availability treats an empty list as permissive`() {
        assertTrue(DeviceCaps.isGovernorAvailable("schedutil", emptyList()))
        assertTrue(DeviceCaps.isGovernorAvailable("schedutil", listOf("schedutil", "performance")))
        assertFalse(DeviceCaps.isGovernorAvailable("ondemand", listOf("schedutil", "performance")))
    }
}
