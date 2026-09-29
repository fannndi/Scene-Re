package com.omarea.core.profile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePlannerTest {

    private val caps = DeviceCaps(
        freqs = mapOf(
            "policy0" to listOf(300000L, 576000L, 1804800L),
            "policy6" to listOf(300000L, 2304000L)
        ),
        governors = mapOf(
            "policy0" to listOf("schedutil", "performance"),
            "policy6" to listOf("schedutil")
        )
    )

    private val json = JSONObject(
        """
        {
          "init": {
            "core_ctl": { "cpu6": { "enable": 1, "min_cpus": 0 } },
            "sched": { "upmigrate": 71 },
            "input_boost": { "0": 1000000, "7": 0, "ms": 40 }
          },
          "profiles": {
            "custom": {
              "cpu": {
                "policy0": { "governor": "schedutil", "min": 5000, "max": 2500000 },
                "policy6": { "governor": "ondemand", "max": 2304000, "hispeed": 5000 }
              },
              "cores_online": { "6": 1 },
              "input_boost": { "0": 0, "ms": 0 },
              "core_ctl": { "cpu6": "on" },
              "gpu": { "min_pwrlevel": 6, "max_pwrlevel": 0 },
              "ufs": "save",
              "thermal_sconfig": 0
            },
            "release": { "cpu": { "policy0": { "min": 300000, "max": 1804800 } } }
          }
        }
        """.trimIndent()
    )

    private fun values(plan: ProfilePlan, node: String) = plan.ops.firstOrNull { it.node == node }?.value

    @Test
    fun `init plan maps sections to nodes`() {
        val plan = ProfilePlanner.planInit(json, caps)
        assertEquals("1", values(plan, "/sys/devices/system/cpu/cpu6/core_ctl/enable"))
        assertEquals("71", values(plan, "/proc/sys/kernel/sched_upmigrate"))
        assertTrue(plan.ops.any { it.node.endsWith("input_boost_freq") })
        assertTrue(plan.warnings.isEmpty())
    }

    @Test
    fun `profile lookup accepts the legacy fast id`() {
        val plan = ProfilePlanner.planProfile(json, "fast", caps)
        assertEquals("custom", plan.label)
        assertTrue(plan.ops.isNotEmpty())
    }

    @Test
    fun `frequencies are clamped to real OPPs`() {
        val plan = ProfilePlanner.planProfile(json, "custom", caps)
        assertEquals("300000", values(plan, "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq"))
        assertEquals("1804800", values(plan, "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"))
    }

    @Test
    fun `unavailable governors are skipped with a warning`() {
        val plan = ProfilePlanner.planProfile(json, "custom", caps)
        assertNull(values(plan, "/sys/devices/system/cpu/cpufreq/policy6/scaling_governor"))
        assertTrue(plan.warnings.any { it.contains("ondemand") })
    }

    @Test
    fun `core_ctl on expands to the full parameter set`() {
        val plan = ProfilePlanner.planProfile(json, "custom", caps)
        assertEquals("2", values(plan, "/sys/devices/system/cpu/cpu6/core_ctl/max_cpus"))
    }

    @Test
    fun `profile max is handed off when both policies define max`() {
        val plan = ProfilePlanner.planProfile(json, "custom", caps)
        assertEquals(1804800L to 2304000L, plan.profileMax)
    }

    @Test
    fun `missing profile yields an empty plan with a warning`() {
        val plan = ProfilePlanner.planProfile(json, "powersave", caps)
        assertTrue(plan.ops.isEmpty())
        assertTrue(plan.warnings.isNotEmpty())
    }
}
