package com.omarea.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure script assembly for the IRQ-affinity controller.
 *
 * Regression guard: the restart snippet must end with a newline — a missing
 * one glued `done` and the next `echo` together on device and silently
 * aborted the script after the daemon restart.
 */
class IrqAffinityScriptTest {

    @Test
    fun `apply script builds conf step, pins and renicks in order`() {
        val script = IrqAffinity.applyScript(
            patchedConf = "PRIO=1,1,1,1,0,0,0,0\nIGNORED_IRQ=19,21,38,332,115",
            values = mapOf(383 to "6", 127 to "7"),
            daemon = true
        )
        assertTrue(script.startsWith("rm -f /data/local/tmp/scene_irqbalance.conf"))
        assertTrue(script.contains("SCENE_IRQ_CONF\nmount --bind"))
        assertTrue(script.contains("IGNORED_IRQ=19,21,38,332,115"))
        // the restart block must not swallow the affinity writes
        assertFalse(script.contains("doneecho"))
        assertTrue(script.contains("done\necho 6 > /proc/irq/383/smp_affinity_list"))
        assertTrue(script.contains("echo 7 > /proc/irq/127/smp_affinity_list"))
        assertTrue(script.contains("renice -n -10"))
        assertTrue(script.indexOf("echo 6 >") < script.indexOf("renice -n -10"))
    }

    @Test
    fun `already mounted skips the conf step`() {
        val script = IrqAffinity.applyScript(null, mapOf(383 to "6"), daemon = true)
        assertFalse(script.contains("mount --bind"))
        assertTrue(script.startsWith("echo 6 > /proc/irq/383/smp_affinity_list"))
    }

    @Test
    fun `no daemon means no conf mount, restart or renice`() {
        val script = IrqAffinity.applyScript("PRIO=1", mapOf(127 to "7"), daemon = false)
        // nothing rebalances without the daemon: pin directly, no mount/restart
        assertFalse(script.contains("mount --bind"))
        assertFalse(script.contains("ctl.restart"))
        assertFalse(script.contains("renice"))
        assertTrue(script.startsWith("echo 7 > /proc/irq/127/smp_affinity_list"))
    }

    @Test
    fun `restore unmounts, restarts and resets nice`() {
        val script = IrqAffinity.restoreScript()
        assertTrue(script.startsWith("umount /vendor/etc/msm_irqbalance.conf"))
        assertTrue(script.contains("rm -f /data/local/tmp/scene_irqbalance.conf"))
        assertTrue(script.contains("ctl.restart vendor.msm_irqbalance"))
        assertTrue(script.contains("renice -n 0"))
        assertTrue(script.trimEnd().endsWith("fi"))
    }

    @Test
    fun `restart waits for the new pid before returning`() {
        val script = IrqAffinity.applyScript("PRIO=1", mapOf(383 to "6"), daemon = true)
        assertTrue(script.contains("OLD=\$(pidof msm_irqbalance)"))
        assertTrue(script.contains("NEW=\$(pidof msm_irqbalance)"))
        assertTrue(script.contains("break;"))
        // the wait must close before the writer line
        assertTrue(script.contains("done\n"))
        assertEquals(-1, script.indexOf("doneecho"))
    }
}
