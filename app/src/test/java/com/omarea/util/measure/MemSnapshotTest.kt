package com.omarea.util.measure

import org.junit.Assert.assertEquals
import org.junit.Test

class MemSnapshotTest {

    @Test
    fun `parses meminfo keys`() {
        val map = MemSnapshot.parseMemInfo(
            """
            MemTotal:        5741080 kB
            MemAvailable:    1716224 kB
            Cached:          1615824 kB
            Dirty:             18096 kB
            """.trimIndent()
        )
        assertEquals(5741080L, map["MemTotal"])
        assertEquals(1716224L, map["MemAvailable"])
        assertEquals(1615824L, map["Cached"])
        assertEquals(18096L, map["Dirty"])
    }

    @Test
    fun `parses zram mm_stat`() {
        val fields = MemSnapshot.parseMmStat("706387968 167064882 174997504 0 227065856 14079 15520 0 0 5572")
        assertEquals(706387968L, fields[0])
        assertEquals(167064882L, fields[1])
        assertEquals(174997504L, fields[2])
    }

    @Test
    fun `parses zram used kb from proc swaps`() {
        val swaps = "Filename\t\t\t\tType\t\tSize\tUsed\tPriority\n" +
                "/dev/block/zram0                        partition\t4194300\t691028\t-2"
        assertEquals(691028L, MemSnapshot.parseZramUsedKb(swaps))
        assertEquals(0L, MemSnapshot.parseZramUsedKb("Filename Type Size Used Priority"))
    }

    @Test
    fun `derived metrics are exact`() {
        val snap = MemSnapshot.Snapshot(
            memTotalKb = 5741080,
            memAvailableKb = 1716224,
            memFreeKb = 142764,
            cachedKb = 1615824,
            swapCachedKb = 6380,
            dirtyKb = 18096,
            buffersKb = 2624,
            swapTotalKb = 4194300,
            swapFreeKb = 3503272,
            zramDisksizeBytes = 4294967296,
            zramOrigBytes = 706387968,
            zramComprBytes = 167064882,
            zramMemUsedBytes = 174997504,
            zramUsedKb = 691028,
            source = "test"
        )
        assertEquals(70, snap.usedPercent)
        assertEquals(674L, snap.zramUsedMb)
        assertEquals(166L, snap.zramMemUsedMb)
        assertEquals(4.228, snap.zramCompression!!, 0.001)
        assertEquals(4096L, snap.zramTotalMb)
        assertEquals(691028L, snap.swapUsedKb)
    }
}
