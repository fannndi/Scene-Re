package com.omarea.core.profile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifyPolicyTest {

    @Test
    fun `lower max is accepted (thermal mitigation)`() {
        assertTrue(
            VerifyPolicy.isAcceptedMismatch(
                "/sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq", "1843200", "1209600"
            )
        )
    }

    @Test
    fun `higher max is a real failure`() {
        assertFalse(
            VerifyPolicy.isAcceptedMismatch(
                "/sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq", "1209600", "1843200"
            )
        )
    }

    @Test
    fun `min and governor mismatches are always real`() {
        assertFalse(
            VerifyPolicy.isAcceptedMismatch(
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq", "300000", "576000"
            )
        )
        assertFalse(
            VerifyPolicy.isAcceptedMismatch(
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_governor", "schedutil", "performance"
            )
        )
    }

    @Test
    fun `non-numeric values are never auto-accepted`() {
        assertFalse(
            VerifyPolicy.isAcceptedMismatch(
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq", "schedutil", "performance"
            )
        )
    }
}
