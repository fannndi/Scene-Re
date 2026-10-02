package com.omarea.engine

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

    @Test
    fun `mid OPP mirrors Encore's which_midfreq`() {
        // Odd count: the median.
        assertEquals(3L, DeviceCaps.midFreq(listOf(1L, 2L, 3L, 4L, 5L)))
        // Even count: the upper middle (3rd from the top for 6 OPPs).
        assertEquals(4L, DeviceCaps.midFreq(listOf(1L, 2L, 3L, 4L, 5L, 6L)))
        assertEquals(null, DeviceCaps.midFreq(emptyList()))
    }

    @Test
    fun `first available congestion algorithm`() {
        assertEquals(
            "westwood",
            DeviceCaps.firstAvailableCc(listOf("bbr", "westwood", "cubic"), listOf("cubic", "westwood"))
        )
        assertEquals("cubic", DeviceCaps.firstAvailableCc(listOf("bbr", "cubic"), listOf("cubic", "reno")))
        assertEquals(null, DeviceCaps.firstAvailableCc(listOf("bbr"), listOf("cubic", "reno")))
    }

    @Test
    fun `parses the batch probe output`() {
        val sample = listOf(
            "@@freqs@@",
            "policy0|1804800 300000 1017600",
            "policy6|2304000 300000",
            "@@governors@@",
            "policy0|schedutil performance",
            "policy6|schedutil",
            "@@tcpcc@@",
            "cubic reno",
            "@@scheduler@@",
            "[cfq] noop deadline",
            "@@devfreq@@",
            "soc:qcom,cpu0-cpu-l3-lat|300 500 700|mem_latency compute",
            "soc:qcom,cpu6-cpu-ddr-latfloor|100 200|compute",
            "soc:qcom,cpu-llcc-ddr-bw||",
            "malformed line",
            ""
        ).joinToString("\n")

        val caps = DeviceCaps.parse(sample)

        assertEquals(listOf(300000L, 1017600L, 1804800L), caps.freqs["policy0"])
        assertEquals(listOf(300000L, 2304000L), caps.freqs["policy6"])
        assertEquals(listOf("schedutil", "performance"), caps.governors["policy0"])
        assertEquals(listOf("cubic", "reno"), caps.tcpCc)
        assertEquals(listOf("cfq", "noop", "deadline"), caps.blockSchedulers)
        // Ascending OPPs, empty domains dropped, malformed lines ignored.
        assertEquals(listOf(300L, 500L, 700L), caps.devfreqLatency["soc:qcom,cpu0-cpu-l3-lat"])
        assertEquals(listOf(100L, 200L), caps.devfreqLatency["soc:qcom,cpu6-cpu-ddr-latfloor"])
        assertFalse(caps.devfreqLatency.containsKey("soc:qcom,cpu-llcc-ddr-bw"))
        assertEquals(2, caps.devfreqLatency.size)
        assertEquals(
            listOf("mem_latency", "compute"),
            caps.devfreqGovernors["soc:qcom,cpu0-cpu-l3-lat"]
        )
        assertEquals(listOf("compute"), caps.devfreqGovernors["soc:qcom,cpu6-cpu-ddr-latfloor"])
    }
}
