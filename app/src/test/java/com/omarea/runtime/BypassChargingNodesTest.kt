package com.omarea.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bypass-charging node preference contract (ROM evidence: MIUI mishow.sh uses
 * `battery/input_suspend`; `battery_charging_enabled` is 0777 and unused by
 * any ROM daemon on surya).
 */
class BypassChargingNodesTest {

    @Test
    fun `auto prefers the true-bypass node then the mishow pause node`() {
        val auto = BypassCharging.nodesFor(BypassCharging.MODE_AUTO)
        assertEquals(
            "/sys/class/power_supply/battery/battery_charging_enabled",
            auto.first().path
        )
        assertTrue(auto.any { it.path == "/sys/class/power_supply/battery/input_suspend" })
    }

    @Test
    fun `bypass mode uses only battery_charging_enabled`() {
        val nodes = BypassCharging.nodesFor(BypassCharging.MODE_BYPASS)
        assertEquals(1, nodes.size)
        assertEquals(
            "/sys/class/power_supply/battery/battery_charging_enabled",
            nodes.first().path
        )
    }

    @Test
    fun `pause mode uses only the MIUI mishow node`() {
        val nodes = BypassCharging.nodesFor(BypassCharging.MODE_PAUSE)
        assertEquals(1, nodes.size)
        assertEquals("/sys/class/power_supply/battery/input_suspend", nodes.first().path)
    }

    @Test
    fun `reset list covers every candidate without duplicates`() {
        val paths = BypassCharging.CANDIDATES.map { it.path }
        assertEquals(paths.size, paths.toSet().size)
        assertTrue(paths.contains("/sys/class/power_supply/battery/battery_charging_enabled"))
        assertTrue(paths.contains("/sys/class/power_supply/battery/input_suspend"))
        assertTrue(paths.contains("/sys/class/qcom-battery/input_suspend"))
    }
}
