package com.omarea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compat registry is the app's kernel/ROM contract: a feature whose nodes
 * are missing on the running kernel must be reported as locked instead of
 * letting tuning keys silently no-op.
 */
class KernelCompatTest {

    @Test
    fun `parse reads kernel, features and configs`() {
        val output = """
            @@kernel@@
            4.14.180-perf
            @@features@@
            core_ctl=1
            sched_walt=1
            fpsgo=0
            perfmgr=0
            vm_page_cluster=0
            @@configs@@
            CONFIG_CPU_BOOST=y
            CONFIG_MIGT=y
        """.trimIndent()

        val snap = KernelCompat.parse(output)
        assertEquals("4.14.180-perf", snap.kernel)
        assertTrue("core_ctl" in snap.available)
        assertTrue(snap.isLocked("fpsgo"))
        assertTrue(snap.isLocked("perfmgr"))
        assertTrue(snap.isLocked("vm_page_cluster"))
        assertEquals("y", snap.configs["CONFIG_CPU_BOOST"])
        assertEquals("y", snap.configs["CONFIG_MIGT"])

        val locked = snap.locked.map { it.id }
        assertTrue("fpsgo" in locked)
        assertTrue("core_ctl" !in locked)
    }

    @Test
    fun `empty or garbage output locks everything without crashing`() {
        val snap = KernelCompat.parse("")
        assertEquals("", snap.kernel)
        assertTrue(snap.available.isEmpty())
        assertEquals(KernelCompat.features.size, snap.locked.size)
    }

    @Test
    fun `registry ids are unique and probes non-empty`() {
        val ids = KernelCompat.features.map { it.id }
        assertEquals("duplicate feature id", ids.size, ids.toSet().size)
        for (f in KernelCompat.features) {
            assertTrue("feature ${f.id} has no probes", f.probes.isNotEmpty())
            assertTrue("feature ${f.id} has no label", f.label.isNotBlank())
        }
    }

    @Test
    fun `probe script covers every feature and config key`() {
        val script = KernelCompat.probeScript()
        for (id in KernelCompat.features.map { it.id }) {
            assertTrue("missing $id", script.contains("echo \"$id="))
        }
        for (key in KernelCompat.configKeys) {
            assertTrue("missing config $key", script.contains(key))
        }
        assertTrue(script.contains("@@kernel@@"))
        assertTrue(script.contains("@@features@@"))
        assertTrue(script.contains("@@configs@@"))
        // non-empty probes use -s, existence probes use -e
        assertTrue(script.contains("[ -s \"/sys/devices/platform/soc/1d84000.ufshc/health_descriptor/life_time_estimation_a\" ]"))
        assertTrue(script.contains("[ -e \"/sys/class/kgsl/kgsl-3d0/default_pwrlevel\" ]"))
    }

    @Test
    fun `locked path maps back to its feature`() {
        val locked = setOf("perfmgr", "fpsgo", "bus_dcvs")
        assertEquals(
            "perfmgr",
            KernelCompat.lockedFeatureForPath("/sys/module/perfmgr/parameters/perfmgr_enable", locked)?.id
        )
        assertEquals(
            "fpsgo",
            KernelCompat.lockedFeatureForPath("/sys/kernel/fpsgo/fstb/fpsgo_status", locked)?.id
        )
        assertEquals(
            "bus_dcvs",
            KernelCompat.lockedFeatureForPath("/sys/devices/system/cpu/bus_dcvs/DDR/boost_freq", locked)?.id
        )
        // available features never map even if the path matches
        assertNull(KernelCompat.lockedFeatureForPath("/sys/kernel/fpsgo/x", setOf()))
    }

    @Test
    fun `locked json keys map to their feature with longest-prefix wins`() {
        val locked = setOf("vm_page_cluster", "vm_basic", "kgsl_throttling", "kgsl_pwrlevels", "ufs_control")
        assertEquals("vm_page_cluster", KernelCompat.lockedFeatureForPath("vm.page_cluster", locked)?.id)
        assertEquals("vm_basic", KernelCompat.lockedFeatureForPath("vm.dirty_ratio", locked)?.id)
        assertEquals("kgsl_throttling", KernelCompat.lockedFeatureForPath("gpu.throttling", locked)?.id)
        assertEquals("kgsl_pwrlevels", KernelCompat.lockedFeatureForPath("gpu.max_pwrlevel", locked)?.id)
        assertEquals("ufs_control", KernelCompat.lockedFeatureForPath("ufs", locked)?.id)
        // unknown key stays unmapped
        assertNull(KernelCompat.lockedFeatureForPath("battery.profile", locked))
    }

    @Test
    fun `report mentions locked ids and port hints`() {
        val snap = KernelCompat.Snapshot(
            kernel = "4.14.180-test",
            available = setOf("core_ctl"),
            configs = emptyMap()
        )
        val report = KernelCompat.report(snap)
        assertTrue(report.contains("locked"))
        assertTrue(report.contains("fpsgo"))
        assertTrue(report.contains("port:"))

        val clean = KernelCompat.Snapshot("k", KernelCompat.features.map { it.id }.toSet(), emptyMap())
        assertTrue(KernelCompat.report(clean).contains("all"))
    }
}
