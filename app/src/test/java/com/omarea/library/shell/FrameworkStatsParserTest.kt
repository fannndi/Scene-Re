package com.omarea.library.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [FrameworkStatsParser].
 *
 * The input fixtures are real `dumpsys` output captured from the target device
 * (POCO X3 NFC, surya, MIUI 13 / Android 12), so these tests guard against the ROM-specific
 * formatting that a phone-only parser would silently get wrong.
 */
class FrameworkStatsParserTest {

    // Captured with: adb shell head -1 /proc/stat
    private val procStatLine =
        "cpu  9656652 1797725 5391173 17267565 37050 906885 470458 0 0 0"

    // Captured with: adb shell dumpsys cpuinfo
    private val cpuInfoDump = """
        Load: 0.01 / 0.06 / 0.14
        CPU usage from 319844ms to 19773ms ago (2026-09-22 13:47:45.573 to 2026-09-22 13:52:45.644):
          2.6% 14220/com.google.android.googlequicksearchbox:googleapp: 2.4% user + 0.2% kernel / faults: 32786 minor 14 major
          2.2% 1558/system_server: 1.3% user + 0.9% kernel / faults: 13640 minor 7 major
          0.9% 28519/kworker/u16:3: 0% user + 0.9% kernel
          0.7% 1081/surfaceflinger: 0.6% user + 0.1% kernel / faults: 20 minor
          7.3% TOTAL: 4.4% user + 2.2% kernel + 0.2% iowait + 0.2% irq + 0.2% softirq
    """.trimIndent()

    // Captured with: adb shell dumpsys gfxinfo com.omarea.vtools
    private val gfxInfoDump = """
        ** Graphics info for pid 31640 [com.omarea.vtools] **
        Total frames rendered: 43
        Janky frames: 13 (30.23%)
        Janky frames (legacy): 14 (32.56%)
        50th percentile: 12ms
        90th percentile: 150ms
        95th percentile: 250ms
        99th percentile: 1000ms
        50th gpu percentile: 6ms
        90th gpu percentile: 9ms
        95th gpu percentile: 10ms
    """.trimIndent()

    // Captured with: adb shell dumpsys meminfo
    private val memInfoDump = """
        Applications Memory Usage (in Kilobytes):
        Uptime: 158821460 Realtime: 271829093

        Total PSS by process:
            589,032K: com.tokopedia.tkpd (pid 30537)
            413,072K: system (pid 1558)
            332,896K: com.facebook.orca (pid 28253)
            281,452K: com.android.settings (pid 29956 / activities)
        Total RAM: 5,752,880K (status normal)
         Free RAM: 3,604,529K (  843,117K cached pss + 2,512,844K cached kernel +   248,568K free)
         Used RAM: 3,852,164K (3,157,500K used pss +   694,664K kernel)
         Lost RAM:   179,390K
    """.trimIndent()

    // Captured with: adb shell dumpsys activity activities
    private val activityDump = """
        ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
          * Task{122d54d #675 type=standard A=10380:com.omarea.vtools}
            mResumedActivity: ActivityRecord{122d54d u0 com.omarea.vtools/.activities.ActivityMain t675}
            mFocusedApp=ActivityRecord{122d54d u0 com.omarea.vtools/.activities.ActivityMain t675}
    """.trimIndent()

    @Test
    fun `parses proc stat cpu line`() {
        val ticks = FrameworkStatsParser.parseCpuTicks(procStatLine)
        assertNotNull(ticks)
        // idle + iowait
        assertEquals(17267565L + 37050L, ticks!!.idle)
        // Sum of all reported jiffies on the line.
        assertEquals(35527508L, ticks.total)
    }

    @Test
    fun `rejects malformed proc stat input`() {
        assertNull(FrameworkStatsParser.parseCpuTicks("not a cpu line"))
        assertNull(FrameworkStatsParser.parseCpuTicks(""))
        assertNull(FrameworkStatsParser.parseCpuTicks("cpu  1 2"))
    }

    @Test
    fun `computes cpu load from two samples`() {
        val first = FrameworkStatsParser.CpuTicks(total = 1000L, idle = 800L)
        val second = FrameworkStatsParser.CpuTicks(total = 1200L, idle = 880L)
        // 200 jiffies elapsed, 80 idle, so 120 busy => 60 percent.
        assertEquals(60, FrameworkStatsParser.cpuLoadPercent(first, second))
    }

    @Test
    fun `returns unknown load when there is no baseline`() {
        val current = FrameworkStatsParser.CpuTicks(total = 1000L, idle = 800L)
        assertEquals(-1, FrameworkStatsParser.cpuLoadPercent(null, current))
    }

    @Test
    fun `ignores counter resets that would produce a negative delta`() {
        val first = FrameworkStatsParser.CpuTicks(total = 1200L, idle = 880L)
        val second = FrameworkStatsParser.CpuTicks(total = 1000L, idle = 800L)
        assertEquals(-1, FrameworkStatsParser.cpuLoadPercent(first, second))
    }

    @Test
    fun `parses cpuinfo rows and skips the total line`() {
        val entries = FrameworkStatsParser.parseCpuInfo(cpuInfoDump)
        assertEquals(4, entries.size)
        assertEquals("com.google.android.googlequicksearchbox:googleapp", entries[0].packageName)
        assertEquals(2.6f, entries[0].cpuPercent, 0.001f)
        // system_server has no '/' package part beyond the pid, so the pid is stripped.
        assertEquals("system_server", entries[1].packageName)
        // Kernel threads keep their name.
        assertEquals("kworker/u16:3", entries[2].packageName)
        assertTrue(entries.none { it.packageName.startsWith("TOTAL") })
    }

    @Test
    fun `honours the cpuinfo entry limit`() {
        val entries = FrameworkStatsParser.parseCpuInfo(cpuInfoDump, limit = 2)
        assertEquals(2, entries.size)
    }

    @Test
    fun `parses the device total cpu load from cpuinfo`() {
        // Used as the fallback when /proc/stat is denied to the app uid. The components are
        // summed: 4.4 + 2.2 + 0.2 + 0.2 + 0.2 = 7.2.
        assertEquals(7, FrameworkStatsParser.parseTotalCpuLoad(cpuInfoDump))
    }

    @Test
    fun `parses a real captured total cpu load line`() {
        // Captured from the device: "6.1% TOTAL: 3% user + 2.4% kernel + 0% iowait + 0.4% irq + 0.2% softirq"
        val real = "6.1% TOTAL: 3% user + 2.4% kernel + 0% iowait + 0.4% irq + 0.2% softirq"
        assertEquals(6, FrameworkStatsParser.parseTotalCpuLoad(real))
    }

    @Test
    fun `returns null when cpuinfo has no total line`() {
        assertNull(FrameworkStatsParser.parseTotalCpuLoad("  2.6% 14220/some.app: 2.4% user + 0.2% kernel"))
        assertNull(FrameworkStatsParser.parseTotalCpuLoad("TOTAL: nothing numeric here"))
        assertNull(FrameworkStatsParser.parseTotalCpuLoad(""))
    }

    @Test
    fun `clamps an implausible total cpu load`() {
        // Guards against a ROM reporting components that sum above 100.
        assertEquals(100, FrameworkStatsParser.parseTotalCpuLoad("TOTAL: 80% user + 70% kernel"))
    }

    @Test
    fun `parses gfxinfo frame statistics`() {
        val stats = FrameworkStatsParser.parseGpuFrameStats("com.omarea.vtools", gfxInfoDump)
        assertNotNull(stats)
        assertEquals(43, stats!!.totalFrames)
        // The legacy jank line must not overwrite the real count.
        assertEquals(13, stats.jankyFrames)
        assertEquals(150f, stats.percentile90Ms, 0.001f)
        assertEquals(250f, stats.percentile95Ms, 0.001f)
        assertEquals(1000f, stats.percentile99Ms, 0.001f)
        assertEquals(30.23f, stats.jankPercent, 0.01f)
    }

    @Test
    fun `does not confuse gpu percentiles with frame percentiles`() {
        val stats = FrameworkStatsParser.parseGpuFrameStats("pkg", gfxInfoDump)
        // "90th gpu percentile: 9ms" must not be read as the 90th frame percentile.
        assertEquals(150f, stats!!.percentile90Ms, 0.001f)
    }

    @Test
    fun `returns null when gfxinfo has no frame data`() {
        assertNull(FrameworkStatsParser.parseGpuFrameStats("pkg", "No process found for: pkg"))
    }

    @Test
    fun `parses process memory including pid qualifiers`() {
        val map = FrameworkStatsParser.parseProcessMemory(memInfoDump)
        assertEquals(4, map.size)
        assertEquals(589032L, map["com.tokopedia.tkpd"])
        assertEquals(413072L, map["system"])
        // This row carries a "(pid 29956 / activities)" qualifier and must still parse.
        assertEquals(281452L, map["com.android.settings"])
    }

    @Test
    fun `parses ram totals`() {
        val totals = FrameworkStatsParser.parseRamTotals(memInfoDump)
        assertNotNull(totals)
        assertEquals(5752880L, totals!!.totalKb)
        assertEquals(3604529L, totals.freeKb)
        assertEquals(3852164L, totals.usedKb)
        assertEquals(179390L, totals.lostKb)
    }

    @Test
    fun `parses foreground package from resumed activity`() {
        assertEquals(
            "com.omarea.vtools",
            FrameworkStatsParser.parseForegroundPackage(activityDump)
        )
    }

    @Test
    fun `falls back to focused app when no resumed activity is present`() {
        val withoutResumed = activityDump.replace(
            "mResumedActivity: ActivityRecord{122d54d u0 com.omarea.vtools/.activities.ActivityMain t675}",
            ""
        )
        assertEquals(
            "com.omarea.vtools",
            FrameworkStatsParser.parseForegroundPackage(withoutResumed)
        )
    }

    @Test
    fun `returns null when no foreground package is found`() {
        assertNull(FrameworkStatsParser.parseForegroundPackage("no activity info here"))
    }

    @Test
    fun `never throws on empty input`() {
        assertNull(FrameworkStatsParser.parseCpuTicks(""))
        assertTrue(FrameworkStatsParser.parseCpuInfo("").isEmpty())
        assertNull(FrameworkStatsParser.parseGpuFrameStats("pkg", ""))
        assertTrue(FrameworkStatsParser.parseProcessMemory("").isEmpty())
        assertNull(FrameworkStatsParser.parseRamTotals(""))
        assertNull(FrameworkStatsParser.parseForegroundPackage(""))
        assertNull(FrameworkStatsParser.parseTotalCpuLoad(""))
    }
}
