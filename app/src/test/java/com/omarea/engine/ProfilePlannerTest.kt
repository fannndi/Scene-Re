package com.omarea.engine

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
            "input_boost": { "0": 1000000, "7": 0, "ms": 40 },
            "powerkey_input_boost": { "0": 1000000, "ms": 400, "sched_boost_on_powerkey_input": 0 }
          },
          "profiles": {
            "custom": {
              "cpu": {
                "policy0": { "governor": "schedutil", "min": 5000, "max": 2500000 },
                "policy6": { "governor": "ondemand", "max": 2304000, "hispeed": 5000 }
              },
              "cores_online": { "6": 1 },
              "input_boost": { "0": 0, "ms": 0, "sched_boost_on_input": 1 },
              "core_ctl": { "cpu6": "on" },
              "gpu": { "min_pwrlevel": 6, "max_pwrlevel": 0, "default_pwrlevel": 6, "throttling": 1 },
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
    fun `encore packs map to sysctl and queue nodes`() {
        val doc = JSONObject(
            """
            {"init":{
              "net":{"tcp_congestion":["bbr","cubic"],"tcp_fastopen":3},
              "kernel":{"sched_nr_migrate":32,"bogus":1},
              "io":{"sda":{"iostats":0},"sdb":{"iostats":1,"bogus":2}},
              "sched_lib":{"sched_lib_name":"libunity.so","sched_lib_mask_force":255}
            }}
            """.trimIndent()
        )
        val ccCaps = caps.copy(tcpCc = listOf("cubic", "reno"))
        val plan = ProfilePlanner.planInit(doc, ccCaps)
        assertEquals("cubic", values(plan, "/proc/sys/net/ipv4/tcp_congestion_control"))
        assertEquals("3", values(plan, "/proc/sys/net/ipv4/tcp_fastopen"))
        assertEquals("32", values(plan, "/proc/sys/kernel/sched_nr_migrate"))
        assertEquals("0", values(plan, "/sys/block/sda/queue/iostats"))
        assertEquals("1", values(plan, "/sys/block/sdb/queue/iostats"))
        assertEquals("255", values(plan, "/proc/sys/kernel/sched_lib_mask_force"))
        assertEquals("libunity.so", values(plan, "/proc/sys/kernel/sched_lib_name"))
        assertTrue(plan.warnings.any { it.contains("bogus") })
    }

    @Test
    fun `cc preference without a match warns instead of writing`() {
        val doc = JSONObject("""{"init":{"net":{"tcp_congestion":["bbr"]}}}""")
        val plan = ProfilePlanner.planInit(doc, caps.copy(tcpCc = listOf("cubic")))
        assertNull(values(plan, "/proc/sys/net/ipv4/tcp_congestion_control"))
        assertTrue(plan.warnings.any { it.contains("congestion") })
    }

    @Test
    fun `kgsl extras and adrenoboost map to their nodes`() {
        val doc = JSONObject(
            """
            {"profiles":{"performance":{"gpu":{
              "bus_split":0,"force_clk_on":1,"adrenoboost":1,
              "min_pwrlevel":5,"max_pwrlevel":0
            }}}}
            """.trimIndent()
        )
        val plan = ProfilePlanner.planProfile(doc, "performance", caps)
        assertEquals("0", values(plan, "/sys/class/kgsl/kgsl-3d0/bus_split"))
        assertEquals("1", values(plan, "/sys/class/kgsl/kgsl-3d0/force_clk_on"))
        assertEquals("1", values(plan, "/sys/class/kgsl/kgsl-3d0/devfreq/adrenoboost"))
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

    @Test
    fun `gpu idle level and throttling are mapped`() {
        val plan = ProfilePlanner.planProfile(json, "custom", caps)
        assertEquals("6", values(plan, "/sys/class/kgsl/kgsl-3d0/default_pwrlevel"))
        assertEquals("1", values(plan, "/sys/class/kgsl/kgsl-3d0/throttling"))
        // unknown keys are simply absent -> no ops
        assertNull(values(plan, "/sys/class/kgsl/kgsl-3d0/thermal_pwrlevel"))
    }

    @Test
    fun `sched boost toggles are mapped`() {
        val init = ProfilePlanner.planInit(json, caps)
        // ms=40 derives sched_boost_on_input=1
        assertEquals("1", values(init, "/sys/module/cpu_boost/parameters/sched_boost_on_input"))
        // explicit powerkey toggle
        assertEquals("0", values(init, "/sys/module/cpu_boost/parameters/sched_boost_on_powerkey_input"))
    }

    @Test
    fun `explicit sched_boost_on_input wins over the ms-derived default`() {
        // custom profile: ms=0 but explicit sched_boost_on_input=1
        val plan = ProfilePlanner.planProfile(json, "custom", caps)
        assertEquals("1", values(plan, "/sys/module/cpu_boost/parameters/sched_boost_on_input"))
    }

    @Test
    fun `core_ctl object form writes every declared key`() {
        val doc = JSONObject(
            """
            {"profiles":{"balance":{"core_ctl":{
              "cpu0":{"enable":1,"min_cpus":4,"not_preferred":"0 0 0 0 1 1","busy_up_thres":60},
              "cpu6":{"enable":0}
            }}}}
            """.trimIndent()
        )
        val plan = ProfilePlanner.planProfile(doc, "balance", caps)
        assertEquals("1", values(plan, "/sys/devices/system/cpu/cpu0/core_ctl/enable"))
        assertEquals("4", values(plan, "/sys/devices/system/cpu/cpu0/core_ctl/min_cpus"))
        assertEquals("0 0 0 0 1 1", values(plan, "/sys/devices/system/cpu/cpu0/core_ctl/not_preferred"))
        assertEquals("60", values(plan, "/sys/devices/system/cpu/cpu0/core_ctl/busy_up_thres"))
        assertEquals("0", values(plan, "/sys/devices/system/cpu/cpu6/core_ctl/enable"))
    }

    @Test
    fun `per-profile hispeed_load and sched_load_boost are mapped`() {
        val doc = JSONObject(
            """
            {"profiles":{"release":{
              "hispeed_load":{"policy0":85,"policy6":85},
              "sched_load_boost":{"cpu6":-6,"cpu7":-6}
            }}}
            """.trimIndent()
        )
        val plan = ProfilePlanner.planProfile(doc, "release", caps)
        assertEquals("85", values(plan, "/sys/devices/system/cpu/cpufreq/policy0/schedutil/hispeed_load"))
        assertEquals("85", values(plan, "/sys/devices/system/cpu/cpufreq/policy6/schedutil/hispeed_load"))
        assertEquals("-6", values(plan, "/sys/devices/system/cpu/cpu6/sched_load_boost"))
        assertEquals("-6", values(plan, "/sys/devices/system/cpu/cpu7/sched_load_boost"))
    }

    @Test
    fun `gpu thermal handoff carries the profile levels`() {
        val plan = ProfilePlanner.planProfile(json, "custom", caps)
        assertEquals(0, plan.profileGpu?.maxPwrLevel)
        assertEquals(6, plan.profileGpu?.defaultPwrLevel)
        assertEquals("1", plan.profileGpu?.throttling)

        val noGpu = ProfilePlanner.planProfile(
            JSONObject("""{"profiles":{"balance":{"cpu":{"policy0":{"max":1000}}}}}"""), "balance", caps
        )
        assertNull(noGpu.profileGpu)
    }
}
