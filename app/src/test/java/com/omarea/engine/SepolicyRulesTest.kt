package com.omarea.engine

import org.junit.Assert.assertEquals
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
        // v2 read families.
        assertTrue(reads.any { it.contains("sysfs_thermal") })
        assertTrue(reads.any { it.contains("vendor_sysfs_scsi_host") })
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
        // v2 families: thermal mailbox, UFS clockscale, LMK, generic sysfs.
        assertTrue(writes.any { it.contains("sysfs_thermal") && it.contains("write") })
        assertTrue(writes.any { it.contains("vendor_sysfs_scsi_host") && it.contains("write") })
        assertTrue(writes.any { it.contains("sysfs_lowmemorykiller") && it.contains("write") })
        assertTrue(writes.any { it == "allow untrusted_app sysfs file { write }" })
        // `proc` writes are deliberately absent: procfs sysctls refuse chmod
        // (DAC blocks the app), so the rule could never work.
        assertTrue(writes.none { it.startsWith("allow untrusted_app proc ") })
    }

    @Test
    fun `write payload leads with writes and ends with sacrificial duplicates`() {
        // This APatch build has been observed trimming *tail* statements of
        // sepolicy.rule; the payload must survive that without losing a rule.
        val payload = SepolicyOptimizer.payload(writes = true)
        val reads = SepolicyOptimizer.statements(writes = false)
        val writes = SepolicyOptimizer.statements(writes = true)
            .filterNot { reads.contains(it) }
        assertTrue("no writes in payload", writes.isNotEmpty())
        assertTrue("writes must come first", payload.take(writes.size).all { writes.contains(it) })
        val tail = payload.takeLast(3)
        assertEquals(3, tail.size)
        assertTrue("sentinel tail must duplicate read rules", tail.all { reads.contains(it) })
        // The read-only payload has no sentinels and no duplicates.
        assertEquals(reads.size, SepolicyOptimizer.payload(writes = false).size)
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
