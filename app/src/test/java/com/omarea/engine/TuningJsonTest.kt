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
                if (name != "custom") {
                    // Efficiency profiles must be able to reach the lowest OPP.
                    assertEquals("$name/$policy min", 300000L, cfg.optLong("min"))
                }
            }
        }
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
