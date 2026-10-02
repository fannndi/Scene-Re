package com.omarea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StockSnapshotTest {

    @Test
    fun `script marks every node and parse round-trips values`() {
        val nodes = listOf("/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq", "/proc/sys/vm/dirty_ratio")
        val script = StockSnapshot.buildScript(nodes)
        assertTrue(script.contains("@@node:/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"))

        val output = """
            @@node:/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq
            1804800
            @@end
            @@node:/proc/sys/vm/dirty_ratio
            20
            @@end
        """.trimIndent()
        assertEquals(
            mapOf(
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq" to "1804800",
                "/proc/sys/vm/dirty_ratio" to "20"
            ),
            StockSnapshot.parse(output)
        )
    }

    @Test
    fun `empty and missing nodes are skipped`() {
        val output = """
            @@node:/a
            @@end
            @@node:/b
            0 1 1 1 1 1
            @@end
        """.trimIndent()
        assertEquals(mapOf("/b" to "0 1 1 1 1 1"), StockSnapshot.parse(output))
    }

    @Test
    fun `nodes list covers the ROM-owned families`() {
        val nodes = StockSnapshot.nodes
        assertTrue(nodes.contains("/sys/module/cpu_boost/parameters/input_boost_freq"))
        assertTrue(nodes.contains("/sys/devices/system/cpu/cpu0/core_ctl/min_cpus"))
        assertTrue(nodes.contains("/dev/cpuset/foreground/boost/cpus"))
        assertTrue(nodes.contains("/sys/class/thermal/thermal_message/sconfig"))
        assertTrue(nodes.contains("/sys/class/kgsl/kgsl-3d0/default_pwrlevel"))
        assertTrue(nodes.contains("/proc/sys/vm/dirty_background_ratio"))
    }
}
