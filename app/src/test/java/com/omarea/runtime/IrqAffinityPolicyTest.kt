package com.omarea.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure IRQ-affinity planning on real device evidence (surya / MIUI 12).
 */
class IrqAffinityPolicyTest {

    // Two real lines from /proc/interrupts of the target device.
    private val sample = """
            127:      32209        268      14016      19358          0          0          0          0   PDC-GIC 115 Edge      msm_drm
            383:      11429       1224       8616      21453          0          0          0          0   PDC-GIC 332 Level     kgsl-3d0
            4:  259022        0        0        0        0        0        0        0  PDC-GIC 19 Edge arch_timer
        """.trimIndent()

    @Test
    fun `parses virq and hwirq for the managed names`() {
        val irqs = IrqAffinityPolicy.parseInterrupts(sample)
        assertEquals(2, irqs.size)
        val drm = irqs.getValue("msm_drm")
        assertEquals(127, drm.virq)
        assertEquals(115, drm.hwirq)
        val kgsl = irqs.getValue("kgsl-3d0")
        assertEquals(383, kgsl.virq)
        assertEquals(332, kgsl.hwirq)
    }

    @Test
    fun `ignores unrelated and malformed lines`() {
        val irqs = IrqAffinityPolicy.parseInterrupts("header line\nCPU0 CPU1\n")
        assertTrue(irqs.isEmpty())
    }

    @Test
    fun `extends the existing ignored list in place`() {
        val conf = """
            PRIO=1,1,1,1,0,0,0,0
            #arch_timer, arm-pmu, arch_mem_timer
            IGNORED_IRQ=19,21,38
        """.trimIndent()
        val out = IrqAffinityPolicy.extendIgnoredIrq(conf, listOf(332, 115))
        assertTrue(out.contains("IGNORED_IRQ=19,21,38,332,115"))
        assertTrue(out.contains("PRIO=1,1,1,1,0,0,0,0"))
        // idempotent: re-extending adds nothing
        val again = IrqAffinityPolicy.extendIgnoredIrq(out, listOf(332, 115))
        assertTrue(again.contains("IGNORED_IRQ=19,21,38,332,115"))
        assertFalse(again.contains("332,115,332"))
    }

    @Test
    fun `appends the ignored line when the conf has none`() {
        val out = IrqAffinityPolicy.extendIgnoredIrq("PRIO=1,1,1,1,0,0,0,0", listOf(332, 115))
        assertTrue(out.contains("IGNORED_IRQ=332,115"))
    }

    @Test
    fun `cpu list validation matches smp_affinity_list syntax`() {
        assertTrue(IrqAffinityPolicy.isValidCpuList("6"))
        assertTrue(IrqAffinityPolicy.isValidCpuList("6-7"))
        assertTrue(IrqAffinityPolicy.isValidCpuList("0,4-5"))
        assertTrue(IrqAffinityPolicy.isValidCpuList("99"))   // syntax valid, range checked by kernel
        assertFalse(IrqAffinityPolicy.isValidCpuList("abc"))
        assertFalse(IrqAffinityPolicy.isValidCpuList(""))
        assertFalse(IrqAffinityPolicy.isValidCpuList("6;rm -rf /"))
    }
}
