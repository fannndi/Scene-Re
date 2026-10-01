package com.omarea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SepolicyCapabilityTest {

    @Test
    fun `every probe family path maps back to its own id`() {
        for (family in SepolicyCapability.families) {
            if (family.id == "sched_vm") continue // root-only by DAC, still mapped below
            assertEquals(family.probeNode, family.id, SepolicyCapability.familyFor(family.probeNode))
        }
    }

    @Test
    fun `family mapping covers all tuning node families`() {
        assertEquals("cpu", SepolicyCapability.familyFor("/sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq"))
        assertEquals("kgsl", SepolicyCapability.familyFor("/sys/class/kgsl/kgsl-3d0/max_pwrlevel"))
        assertEquals("msm_perf", SepolicyCapability.familyFor("/sys/module/msm_performance/parameters/cpu_max_freq"))
        assertEquals("cpu_boost", SepolicyCapability.familyFor("/sys/module/cpu_boost/parameters/input_boost_freq"))
        assertEquals("thermal", SepolicyCapability.familyFor("/sys/class/thermal/thermal_message/sconfig"))
        assertEquals("scsi_host", SepolicyCapability.familyFor("/sys/devices/platform/soc/1d84000.ufshc/clkscale_enable"))
        assertEquals("lmk", SepolicyCapability.familyFor("/sys/module/lowmemorykiller/parameters/minfree"))
        assertEquals("sched_vm", SepolicyCapability.familyFor("/proc/sys/kernel/sched_upmigrate"))
        // Generic sysfs (UFS devfreq, hibern8, ...) falls through to the last bucket.
        assertEquals("sysfs_generic", SepolicyCapability.familyFor("/sys/class/devfreq/1d84000.ufshc/min_freq"))
        assertEquals("sysfs_generic", SepolicyCapability.familyFor("/sys/devices/platform/soc/1d84000.ufshc/hibern8_on_idle_enable"))
        // Non-sysfs paths are unknown.
        assertNull(SepolicyCapability.familyFor("/data/local/tmp/scene_policy.rules"))
        assertNull(SepolicyCapability.familyFor("/sdcard/Scene/profiles/x.tuning.json"))
    }

    @Test
    fun `kgsl devfreq stays in the kgsl family, not generic sysfs`() {
        assertEquals("kgsl", SepolicyCapability.familyFor("/sys/class/kgsl/kgsl-3d0/devfreq/max_freq"))
    }

    @Test
    fun `marks and cache lookups are coherent`() {
        SepolicyCapability.seed(emptyMap())
        assertNull("unknown family result must be null", SepolicyCapability.canWrite("/proc/sys/kernel/sched_upmigrate"))
        SepolicyCapability.mark("/sys/class/thermal/thermal_message/sconfig", false)
        assertEquals(false, SepolicyCapability.canWrite("/sys/class/thermal/thermal_message/cpu_limits"))
        SepolicyCapability.mark("/sys/class/thermal/thermal_message/temp_state", true)
        assertEquals(true, SepolicyCapability.canWrite("/sys/class/thermal/thermal_message/sconfig"))
    }
}
