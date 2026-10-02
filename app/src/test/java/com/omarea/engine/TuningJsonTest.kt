package com.omarea.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the shipped tuning.json: values must be real OPPs on sm6150, thermal
 * sconfigs must exist in the retail ROM, and every profile must carry an
 * explicit hispeed/rate-limit set (no stale governor state across switches).
 */
class TuningJsonTest {

    private val silver = listOf(
        300000L, 576000L, 768000L, 1017600L, 1248000L,
        1324800L, 1497600L, 1612800L, 1708800L, 1804800L
    )
    private val gold = listOf(
        300000L, 652800L, 806400L, 979200L, 1094400L, 1209600L, 1324800L,
        1555200L, 1708800L, 1843200L, 1939200L, 2169600L, 2208000L, 2304000L
    )
    private val caps = DeviceCaps(
        freqs = mapOf("policy0" to silver, "policy6" to gold),
        governors = mapOf(
            "policy0" to listOf("userspace", "powersave", "performance", "schedutil"),
            "policy6" to listOf("userspace", "powersave", "performance", "schedutil")
        ),
        tcpCc = listOf("cubic", "reno"),
        devfreqLatency = mapOf(
            "soc:qcom,cpu0-cpu-l3-lat" to listOf(300000000L, 1459200000L),
            "soc:qcom,cpu6-cpu-l3-lat" to listOf(300000000L, 1459200000L),
            "soc:qcom,cpu0-cpu-ddr-latfloor" to listOf(762L, 6881L),
            "soc:qcom,cpu6-cpu-ddr-latfloor" to listOf(762L, 6881L)
        )
    )

    private val json: JSONObject =
        JSONObject(File("src/main/assets/powercfg/sm6150/tuning.json").readText())

    private val profiles get() = json.getJSONObject("profiles")

    private fun opps(policy: String) = if (policy == "policy0") silver else gold

    @Test
    fun `every profile and init plans without warnings`() {
        assertTrue(ProfilePlanner.planInit(json, caps).warnings.isEmpty())
        for (name in profiles.keys()) {
            val plan = ProfilePlanner.planProfile(json, name, caps)
            assertTrue("$name warnings: ${plan.warnings}", plan.warnings.isEmpty())
            assertTrue("$name produced no ops", plan.ops.isNotEmpty())
        }
    }

    @Test
    fun `frequency values are real OPPs`() {
        for (name in profiles.keys()) {
            val cpu = profiles.getJSONObject(name).optJSONObject("cpu") ?: continue
            for (policy in listOf("policy0", "policy6")) {
                val cfg = cpu.optJSONObject(policy) ?: continue
                for (key in listOf("min", "max", "hispeed")) {
                    if (!cfg.has(key)) continue
                    val value = cfg.optLong(key)
                    assertTrue(
                        "$name/$policy/$key=$value is not an sm6150 OPP",
                        opps(policy).contains(value)
                    )
                }
                if (cfg.has("min") && cfg.has("max")) {
                    assertTrue("$name/$policy min > max", cfg.optLong("min") <= cfg.optLong("max"))
                }
            }
        }
    }

    /**
     * Harmony contract with the ROM: the release profile restores the exact
     * state `init.qcom.post_boot.sh` leaves on surya (soc_id 365/366) so
     * engine OFF really is stock. Values taken from the ROM's moorea block.
     */
    @Test
    fun `release mirrors the ROM post_boot stock`() {
        val rel = profiles.getJSONObject("release")
        assertEquals(576000L, rel.getJSONObject("cpu").getJSONObject("policy0").getLong("min"))
        assertEquals(1248000L, rel.getJSONObject("cpu").getJSONObject("policy0").getLong("hispeed"))
        assertEquals(652800L, rel.getJSONObject("cpu").getJSONObject("policy6").getLong("min"))
        assertEquals(1324800L, rel.getJSONObject("cpu").getJSONObject("policy6").getLong("hispeed"))
        assertEquals(85, rel.getJSONObject("hispeed_load").getInt("policy0"))
        assertEquals(85, rel.getJSONObject("hispeed_load").getInt("policy6"))
        assertEquals(-6, rel.getJSONObject("sched_load_boost").getInt("cpu6"))
        assertEquals(120, rel.getJSONObject("input_boost").getInt("ms"))
        assertEquals(1324800, rel.getJSONObject("input_boost").getInt("0"))
        assertEquals(0, rel.getJSONObject("input_boost").getInt("sched_boost_on_input"))
        assertEquals(400, rel.getJSONObject("powerkey_input_boost").getInt("ms"))
        assertEquals(1804800, rel.getJSONObject("powerkey_input_boost").getInt("4"))
        assertEquals(2208000, rel.getJSONObject("powerkey_input_boost").getInt("7"))
        val cpuset = rel.getJSONObject("cpuset")
        assertEquals("0-2", cpuset.getString("background"))
        assertEquals("0-3", cpuset.getString("system-background"))
        assertEquals("0-2,4-7", cpuset.getString("foreground"))
        assertEquals("4-7", cpuset.getString("foreground/boost"))
        assertEquals("0-7", cpuset.getString("top-app"))
        val coreCtl = rel.getJSONObject("core_ctl")
        assertEquals(1, coreCtl.getJSONObject("cpu0").getInt("enable"))
        assertEquals(4, coreCtl.getJSONObject("cpu0").getInt("min_cpus"))
        assertEquals(60, coreCtl.getJSONObject("cpu0").getInt("busy_up_thres"))
        assertEquals(0, coreCtl.getJSONObject("cpu6").getInt("enable"))
        val vm = rel.getJSONObject("vm")
        assertEquals(10, vm.getInt("dirty_background_ratio"))
        assertEquals(20, vm.getInt("dirty_ratio"))
        assertEquals(50, vm.getInt("overcommit_ratio"))
        assertEquals(100, vm.getInt("swap_ratio"))
        assertEquals(128, vm.getInt("read_ahead_kb"))
        // The planner must be able to express the object-form core_ctl.
        val plan = ProfilePlanner.planProfile(json, "release", caps)
        assertTrue(plan.ops.any { it.node.endsWith("cpu0/core_ctl/min_cpus") && it.value == "4" })
        assertTrue(plan.ops.any { it.node.endsWith("cpu6/core_ctl/enable") && it.value == "0" })
        assertTrue(plan.ops.any { it.node.endsWith("policy6/schedutil/hispeed_load") && it.value == "85" })
        assertEquals(1804800L, plan.profileMax?.first)
        assertEquals(2304000L, plan.profileMax?.second)
    }

    @Test
    fun `encore-derived init packs are present and resolve`() {
        // Network preference list resolves against caps.tcpCc (cubic here),
        // the kernel/jitter sysctls and block-queue knobs map 1:1, sched_lib
        // reports the game libraries with force mask 255.
        val plan = ProfilePlanner.planInit(json, caps)
        assertTrue("init warnings: ${plan.warnings}", plan.warnings.isEmpty())
        fun value(node: String) = plan.ops.firstOrNull { it.node == node }?.value
        assertEquals("cubic", value("/proc/sys/net/ipv4/tcp_congestion_control"))
        assertEquals("3", value("/proc/sys/net/ipv4/tcp_fastopen"))
        assertEquals("1", value("/proc/sys/net/ipv4/tcp_low_latency"))
        assertEquals("32", value("/proc/sys/kernel/sched_nr_migrate"))
        assertEquals("1", value("/proc/sys/kernel/sched_child_runs_first"))
        assertEquals("0", value("/proc/sys/kernel/sched_autogroup_enabled"))
        assertEquals("3", value("/proc/sys/kernel/perf_cpu_time_max_percent"))
        assertEquals("0", value("/proc/sys/kernel/sched_schedstats"))
        assertEquals("15", value("/proc/sys/vm/stat_interval"))
        assertEquals("0", value("/sys/block/sda/queue/iostats"))
        assertEquals("0", value("/sys/block/sda/queue/add_random"))
        assertEquals("255", value("/proc/sys/kernel/sched_lib_mask_force"))
        assertTrue(
            "sched_lib_name misses libunity.so",
            value("/proc/sys/kernel/sched_lib_name")?.contains("libunity.so") == true
        )
    }

    @Test
    fun `devfreq latency is pinned on performance and unlocked elsewhere`() {
        assertEquals("max", profiles.getJSONObject("performance").getJSONObject("devfreq").getString("latency"))
        assertEquals("max", profiles.getJSONObject("custom").getJSONObject("devfreq").getString("latency"))
        for (name in listOf("powersave", "balance", "release")) {
            assertEquals("unlock", profiles.getJSONObject(name).getJSONObject("devfreq").getString("latency"))
        }

        val plan = ProfilePlanner.planProfile(json, "performance", caps)
        val ops = plan.ops.filter { it.node.startsWith("/sys/class/devfreq/soc:qcom,cpu") }
        assertTrue("no devfreq ops", ops.isNotEmpty())
        for ((_, domainOps) in ops.groupBy { it.node.substringBeforeLast('/') }) {
            val max = domainOps.first { it.node.endsWith("/max_freq") }.value
            val min = domainOps.first { it.node.endsWith("/min_freq") }.value
            assertEquals("pinned floor must equal the top OPP", max, min)
        }
    }

    @Test
    fun `kgsl bus force is stock outside tuned profiles`() {
        // performance/custom open the bus and force the GPU clock on; every
        // other profile carries the stock values explicitly so a switch can
        // never leave stale forcing behind.
        for (name in listOf("powersave", "balance", "release")) {
            val gpu = profiles.getJSONObject(name).getJSONObject("gpu")
            assertEquals("$name bus_split", 1, gpu.optInt("bus_split"))
            assertEquals("$name force_clk_on", 0, gpu.optInt("force_clk_on"))
        }
        for (name in listOf("performance", "custom")) {
            val gpu = profiles.getJSONObject(name).getJSONObject("gpu")
            assertEquals("$name bus_split", 0, gpu.optInt("bus_split"))
            assertEquals("$name force_clk_on", 1, gpu.optInt("force_clk_on"))
        }
    }

    @Test
    fun `shipped tuning declares empty mitigations`() {
        // Structure ships, policy stays per-device: nothing is suppressed on
        // surya (all features are device-verified).
        assertEquals(0, json.getJSONArray("mitigations").length())
        assertEquals(0, json.getJSONArray("disabled_keys").length())
        assertTrue(ProfilePlanner.mitigations(json).isEmpty())
    }

    @Test
    fun `thermal sconfig values ship in the ROM`() {
        for (name in profiles.keys()) {
            val sconfig = profiles.getJSONObject(name).optInt("thermal_sconfig", Int.MIN_VALUE)
            if (sconfig == Int.MIN_VALUE) continue
            val entry = ThermalProfiles.find(sconfig)
            assertNotNull("$name: unknown sconfig $sconfig", entry)
            assertTrue("$name: sconfig ${entry!!.sconfig} not shipped", entry.shipped)
        }
    }

    @Test
    fun `tuned profiles carry explicit hispeed and rate limits`() {
        // Determinism: a missing key would leave the previous profile's value
        // in the kernel node (observed stale hispeed_freq on this device).
        for (name in listOf("powersave", "balance", "performance", "custom")) {
            val cpu = profiles.getJSONObject(name).getJSONObject("cpu")
            for (policy in listOf("policy0", "policy6")) {
                val cfg = cpu.getJSONObject(policy)
                for (key in listOf("hispeed", "down_rate_limit_us", "up_rate_limit_us")) {
                    assertTrue("$name/$policy missing $key", cfg.has(key))
                }
            }
        }
    }

    @Test
    fun `gpu power levels are explicit per profile and floors are intentional`() {
        // pwrlevel index: 0=800MHz .. 6=180MHz. min_pwrlevel = deepest allowed
        // clock (idle floor), max_pwrlevel = most performant allowed (0=none).
        val expectedFloors = mapOf(
            "powersave" to 6,     // 180 MHz deep idle
            "balance" to 6,       // 180 MHz deep idle
            "performance" to 5,   // 267 MHz floor: keep game ramp latency low
            "custom" to 5,
            "release" to 6
        )
        for ((name, floor) in expectedFloors) {
            val gpu = profiles.getJSONObject(name).getJSONObject("gpu")
            assertTrue("$name missing min_pwrlevel", gpu.has("min_pwrlevel"))
            assertTrue("$name missing max_pwrlevel", gpu.has("max_pwrlevel"))
            assertEquals("$name gpu idle floor", floor, gpu.optInt("min_pwrlevel"))
            val minPwr = gpu.optInt("min_pwrlevel")
            val maxPwr = gpu.optInt("max_pwrlevel")
            assertTrue("$name pwrlevel range inverted", maxPwr <= minPwr)
            assertTrue("$name pwrlevel out of range", minPwr in 0..6 && maxPwr in 0..6)
            if (gpu.has("default_pwrlevel")) {
                val idle = gpu.optInt("default_pwrlevel")
                assertTrue(
                    "$name idle level $idle outside allowed [$maxPwr..$minPwr]",
                    idle in maxPwr..minPwr
                )
            }
        }
    }

    @Test
    fun `cpu minimums are explicit and battery profiles idle at the floor`() {
        for (name in profiles.keys()) {
            val cpu = profiles.getJSONObject(name).optJSONObject("cpu") ?: continue
            for (policy in listOf("policy0", "policy6")) {
                val cfg = cpu.optJSONObject(policy) ?: continue
                assertTrue("$name/$policy missing min", cfg.has("min"))
                if (name != "custom" && name != "release") {
                    // Efficiency profiles must be able to reach the lowest OPP.
                    // `release` is the stock-restore profile: it mirrors the
                    // ROM post_boot floors instead (see the harmony test).
                    assertEquals("$name/$policy min", 300000L, cfg.optLong("min"))
                }
            }
        }
    }

    @Test
    fun `lmk minfree is present and sane on every non-custom profile`() {
        for (name in listOf("powersave", "balance", "performance", "release")) {
            val minfree = profiles.getJSONObject(name)
                .getJSONObject("lmk")
                .getString("minfree")
            val pages = minfree.split(",").map { it.trim().toInt() }
            assertEquals("$name: need 6 minfree values", 6, pages.size)
            for (i in 1 until pages.size) {
                assertTrue("$name: minfree must ascend: $minfree", pages[i] > pages[i - 1])
            }
            assertTrue("$name: first value implausible: $minfree", pages.first() >= 4096)
            assertTrue("$name: last value implausible: $minfree", pages.last() <= 2_000_000)
        }
        // Character checks: powersave frees memory earlier, performance keeps
        // apps cached longer than the stock curve.
        val ps = profiles.getJSONObject("powersave").getJSONObject("lmk").getString("minfree")
        val perf = profiles.getJSONObject("performance").getJSONObject("lmk").getString("minfree")
        assertTrue(ps.last().digitToInt() > perf.last().digitToInt() ||
            ps.split(",").last().trim().toInt() > perf.split(",").last().trim().toInt())
    }

    @Test
    fun `battery profiles use the efficiency kernels`() {
        // Silver knee cap, no input-boost bursts, deep GPU idle with a cap,
        // UFS power save.
        val powersave = profiles.getJSONObject("powersave")
        assertEquals(1324800L, powersave.getJSONObject("cpu").getJSONObject("policy0").optLong("max"))
        assertEquals(0, powersave.getJSONObject("input_boost").optInt("ms"))
        val psGpu = powersave.getJSONObject("gpu")
        assertEquals(6, psGpu.optInt("default_pwrlevel"))
        assertTrue(psGpu.optInt("max_pwrlevel") > 0) // capped above the top clock
        assertEquals("save", powersave.optString("ufs"))

        val balance = profiles.getJSONObject("balance")
        assertEquals(1497600L, balance.getJSONObject("cpu").getJSONObject("policy0").optLong("max"))
        assertEquals("save", balance.optString("ufs"))

        // Performance skips the disproportional 2.3 GHz gold step but keeps
        // the full range below it.
        val performance = profiles.getJSONObject("performance")
        assertEquals(2208000L, performance.getJSONObject("cpu").getJSONObject("policy6").optLong("max"))
    }
}
