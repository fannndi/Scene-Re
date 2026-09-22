package com.omarea.vtools.kernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the kernel manager parsers and input validation.
 *
 * The cpufreq fixture follows the surya layout reported by the target device
 * (POCO X3 NFC: policy0 = cpu0-5, policy6 = cpu6-7) but is assembled from the marker format the
 * shell script produces, so the parser is checked without touching a device.
 */
class KernelParserTest {

    private val cpuFreqOutput = """
        POLICY=policy0
        CPUS=0-5
        GOV=schedutil
        AGOV=performance powersave schedutil
        MIN=300000
        MAX=1804800
        HMIN=300000
        HMAX=1804800
        AFREQ=1804800 300000 1689600 576000 1478400 768000 1248000 1017600
        POLICY=policy6
        CPUS=6-7
        GOV=performance
        AGOV=performance powersave schedutil
        MIN=300000
        MAX=2304000
        HMIN=300000
        HMAX=2304000
        AFREQ=2304000 300000 1804800
    """.trimIndent()

    @Test
    fun `parses both cpu clusters with sorted frequencies`() {
        val clusters = CpuClusters.parse(cpuFreqOutput)

        assertEquals(2, clusters.size)
        assertEquals(0, clusters[0].index)
        assertEquals("0-5", clusters[0].cpus)
        assertEquals("schedutil", clusters[0].governor)
        assertEquals(300000L, clusters[0].minFreqKHz)
        assertEquals(1804800L, clusters[0].maxFreqKHz)
        assertEquals(300000L, clusters[0].hardwareMinFreqKHz)
        assertEquals(1804800L, clusters[0].hardwareMaxFreqKHz)
        assertEquals(
            listOf(300000L, 576000L, 768000L, 1017600L, 1248000L, 1478400L, 1689600L, 1804800L),
            clusters[0].availableFrequenciesKHz
        )

        assertEquals(6, clusters[1].index)
        assertEquals("6-7", clusters[1].cpus)
        assertEquals("performance", clusters[1].governor)
        assertEquals(2304000L, clusters[1].maxFreqKHz)
        assertEquals(listOf(300000L, 1804800L, 2304000L), clusters[1].availableFrequenciesKHz)
    }

    @Test
    fun `ignores empty output`() {
        assertTrue(CpuClusters.parse("").isEmpty())
    }

    @Test
    fun `parses dumpsys time on battery durations`() {
        assertEquals(
            24L * 60 * 60 * 1000 + 2L * 60 * 60 * 1000 + 3L * 60 * 1000 + 4L * 1000 + 500L,
            KernelBattery.parseDuration("1d 2h 3m 4s 500ms")
        )
        assertEquals(45L * 1000, KernelBattery.parseDuration("45s"))
        assertEquals(90L * 60 * 1000, KernelBattery.parseDuration("1h 30m"))
        assertEquals(0L, KernelBattery.parseDuration(""))
    }

    @Test
    fun `rejects shell metacharacters in text values`() {
        assertTrue(KernelShell.isSafeTextValue("4 4 1 7"))
        assertTrue(KernelShell.isSafeTextValue("msm-adreno-tz"))
        assertTrue(KernelShell.isSafeTextValue("0.5"))

        assertFalse(KernelShell.isSafeTextValue("4;rm -rf /"))
        assertFalse(KernelShell.isSafeTextValue("\$(id)"))
        assertFalse(KernelShell.isSafeTextValue("a\nb"))
        assertFalse(KernelShell.isSafeTextValue("a'b"))
        assertFalse(KernelShell.isSafeTextValue("a>b"))
    }

    @Test
    fun `accepts only absolute node paths`() {
        assertTrue(KernelShell.isSafePath("/proc/sys/vm/swappiness"))
        assertTrue(KernelShell.isSafePath("/sys/class/kgsl/kgsl-3d0/devfreq/governor"))

        assertFalse(KernelShell.isSafePath("proc/sys/vm/swappiness"))
        assertFalse(KernelShell.isSafePath("/proc/sys/vm/swappiness; reboot"))
        assertFalse(KernelShell.isSafePath("/proc/sys/../../etc/passwd"))
        assertFalse(KernelShell.isSafePath("/proc/sys/vm/swappiness\nrm -rf /"))
    }
}
