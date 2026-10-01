package com.omarea.engine

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The APatch magiskpolicy build used on the target device only accepts the
 * classic `allow <src> <tgt> <class> { perms }` statement format — colon
 * format (`src:tgt:class:perms`) silently fails to apply. These tests keep
 * the rule lists honest.
 */
class SepolicyRulesTest {

    private val classic =
        Regex("""^allow \S+ \S+ \S+( \{[^}]+}|\s\S+)*$""")

    @Test
    fun `read statements use classic format`() {
        for (rule in SepolicyOptimizer.statements(writes = false)) {
            assertTrue("not classic: $rule", classic.matches(rule))
            assertTrue("colon format: $rule", !rule.contains(':'))
            assertTrue("must start with allow: $rule", rule.startsWith("allow "))
            // APatch silently drops unbraced permissions (device-verified).
            assertTrue("permissions must be braced: $rule", rule.contains("{ "))
        }
        // Battery/USB supply reads: the fuel-gauge average and uevent dumps
        // must be reachable without a root-shell fallback.
        val reads = SepolicyOptimizer.statements(writes = false)
        assertTrue(reads.any { it.contains("vendor_sysfs_battery_supply") && it.contains("dir") })
        assertTrue(reads.any { it.contains("vendor_sysfs_battery_supply") && it.contains("file") })
        assertTrue(reads.any { it.contains("vendor_sysfs_usb_supply") && it.contains("file") })
        // zRAM stats (read-only display) + /proc/swaps.
        assertTrue(reads.any { it.contains("sysfs_zram") })
        assertTrue(reads.any { it.contains("proc_swaps") })
        // KernelCompat probe targets (LPM + storage devfreq).
        assertTrue(reads.any { it.contains("vendor_sysfs_msm_power") })
        assertTrue(reads.any { it.contains("sysfs_memory") })
    }

    @Test
    fun `write statements use classic format and cover both domains`() {
        val writes = SepolicyOptimizer.statements(writes = true)
        for (rule in writes) {
            assertTrue("not classic: $rule", classic.matches(rule))
            assertTrue("colon format: $rule", !rule.contains(':'))
            // Unbraced perms are silently dropped by the APatch parser —
            // the direct-write mode broke exactly this way (avc-verified).
            assertTrue("permissions must be braced: $rule", rule.contains("{ "))
        }
        // CPU + GPU domains both need write for direct-write mode to be honest.
        assertTrue(writes.any { it.contains("sysfs_devices_system_cpu") && it.contains("write") })
        assertTrue(writes.any { it.contains("vendor_sysfs_kgsl") && it.contains("write") })
    }

    @Test
    fun `write nodes are absolute sysfs paths`() {
        val nodes = SepolicyOptimizer.writeNodes()
        assertTrue(nodes.isNotEmpty())
        for (node in nodes) {
            assertTrue("not absolute: $node", node.startsWith("/sys/"))
        }
        // GPU pwrlevel nodes are the ones the direct-write path was missing.
        assertTrue(nodes.any { it.startsWith("/sys/class/kgsl/") })
        // Both CPU clusters present (policy0 silver, policy6 gold on sm6150).
        assertTrue(nodes.any { it.contains("policy0") })
        assertTrue(nodes.any { it.contains("policy6") })
    }
}
