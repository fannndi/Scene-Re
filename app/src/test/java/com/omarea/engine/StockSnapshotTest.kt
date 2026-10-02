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
    fun `existing but empty nodes are captured as empty`() {
        // `sched_lib_name` stock value is the empty string: without the @@ok
        // marker the restore could never clear it again.
        val script = StockSnapshot.buildScript(listOf("/proc/sys/kernel/sched_lib_name"))
        assertTrue(script.contains("@@ok"))
        val output = """
            @@node:/proc/sys/kernel/sched_lib_name
            @@ok
            @@end
            @@node:/missing
            @@end
        """.trimIndent()
        assertEquals(mapOf("/proc/sys/kernel/sched_lib_name" to ""), StockSnapshot.parse(output))
    }

    @Test
    fun `nodes list covers the Encore-derived families`() {
        val nodes = StockSnapshot.nodes
        assertTrue(nodes.contains("/proc/sys/vm/stat_interval"))
        assertTrue(nodes.contains("/proc/sys/kernel/sched_lib_name"))
        assertTrue(nodes.contains("/proc/sys/kernel/sched_lib_mask_force"))
        assertTrue(nodes.contains("/proc/sys/kernel/sched_nr_migrate"))
        assertTrue(nodes.contains("/proc/sys/net/ipv4/tcp_fastopen"))
        assertTrue(nodes.contains("/sys/block/sda/queue/iostats"))
        assertTrue(nodes.contains("/sys/class/kgsl/kgsl-3d0/bus_split"))
    }

    @Test
    fun `devfreq capture includes the governor`() {
        val script = StockSnapshot.buildScript(emptyList())
        assertTrue(script.contains("for leaf in min_freq max_freq governor"))
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
