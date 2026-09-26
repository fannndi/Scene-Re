package com.omarea.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GovernorCapabilitiesTest {
    private val suryaGovernors = listOf("schedutil", "performance", "powersave", "userspace")

    @Test
    fun picksFirstAvailableInChain() {
        // The surya kernel has no conservative/ondemand: the endurance chain
        // must fall through to schedutil instead of hardcoding conservative.
        assertEquals("schedutil", GovernorCapabilities.pick(suryaGovernors, GovernorCapabilities.POWERSAVE_CHAIN))
        assertEquals("schedutil", GovernorCapabilities.pick(suryaGovernors, GovernorCapabilities.BALANCE_CHAIN))
        assertEquals("performance", GovernorCapabilities.pick(suryaGovernors, GovernorCapabilities.PERFORMANCE_CHAIN))
        assertEquals("", GovernorCapabilities.pick(emptyList(), GovernorCapabilities.BALANCE_CHAIN))
    }

    @Test
    fun stockKernelPrefersConservativeForEndurance() {
        val stock = listOf("conservative", "schedutil", "ondemand", "performance", "powersave")
        assertEquals("conservative", GovernorCapabilities.pick(stock, GovernorCapabilities.POWERSAVE_CHAIN))
        assertEquals("schedutil", GovernorCapabilities.pick(stock, GovernorCapabilities.BALANCE_CHAIN))
    }

    @Test
    fun parsesProbeOutput() {
        val info = GovernorCapabilities.parse(
            "cpu0=schedutil performance powersave\n" +
                "cur0=schedutil\n" +
                "cpu6=schedutil performance\n" +
                "cur6=schedutil\n" +
                "gpu=msm-adreno-tz simple_ondemand\n" +
                "curgpu=msm-adreno-tz\n" +
                "io=[none] mq-deadline kyber\n"
        )
        assertEquals(listOf("schedutil", "performance", "powersave"), info.cpu0)
        assertEquals("schedutil", info.currentCpu0)
        assertEquals(listOf("none", "mq-deadline", "kyber"), info.io)
        assertTrue(info.cpuSelectable)
        assertTrue(info.gpuSelectable)
        assertTrue(info.ioSelectable)
    }

    @Test
    fun locksWhenTheKernelExposesNoChoice() {
        val info = GovernorCapabilities.parse("cpu0=\ncur0=\ncpu6=\ngpu=\nio=\n")
        assertFalse(info.cpuSelectable)
        assertFalse(info.gpuSelectable)
        assertFalse(info.ioSelectable)
        assertEquals("", GovernorCapabilities.resolved(info)["powersave"])
    }

    @Test
    fun fallsBackToSecondBlockDeviceForIo() {
        val info = GovernorCapabilities.parse("io=\nio2=[none] mq-deadline\n")
        assertEquals(listOf("none", "mq-deadline"), info.io)
    }

    @Test
    fun resolvedShowsEveryScenario() {
        val info = GovernorCapabilities.parse(
            "cpu0=schedutil performance powersave\ncpu6=schedutil\ngpu=msm-adreno-tz-v2 msm-adreno-tz\nio=[none]\n"
        )
        val resolved = GovernorCapabilities.resolved(info)
        assertEquals("schedutil", resolved["powersave"])
        assertEquals("schedutil", resolved["balance"])
        assertEquals("performance", resolved["performance"])
        // The GPU chain order decides, not the kernel listing order.
        assertEquals("msm-adreno-tz", resolved["gpu"])
    }
}
