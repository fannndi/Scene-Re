package com.omarea.util.measure

/**
 * One consistent memory sample.
 *
 * Responsibility: parse `/proc/meminfo`, zram `mm_stat` and `/proc/swaps` read
 * in one batch, so RAM and zram numbers belong to the same instant. zram has
 * two honest numbers: uncompressed swap used (`/proc/swaps`) and the physical
 * RAM it costs (`mm_stat[2]`, `mem_used_total` is absent on this kernel).
 *
 * Non-goals: sampling cadence and UI.
 */
object MemSnapshot {
    private const val MEMINFO_PATH = "/proc/meminfo"
    private const val MM_STAT_PATH = "/sys/block/zram0/mm_stat"
    private const val SWAPS_PATH = "/proc/swaps"

    data class Snapshot(
        val memTotalKb: Long,
        val memAvailableKb: Long,
        val memFreeKb: Long,
        val cachedKb: Long,
        val swapCachedKb: Long,
        val dirtyKb: Long,
        val buffersKb: Long,
        val swapTotalKb: Long,
        val swapFreeKb: Long,
        val zramDisksizeBytes: Long,
        val zramOrigBytes: Long,
        val zramComprBytes: Long,
        val zramMemUsedBytes: Long,
        val zramUsedKb: Long,
        val source: String
    ) {
        val usedKb: Long get() = (memTotalKb - memAvailableKb).coerceAtLeast(0)
        val usedPercent: Int get() = if (memTotalKb > 0) ((usedKb * 100) / memTotalKb).toInt() else 0
        val totalMb: Long get() = memTotalKb / 1024
        val swapUsedKb: Long get() = (swapTotalKb - swapFreeKb).coerceAtLeast(0)
        val zramTotalMb: Long get() = zramDisksizeBytes / (1024 * 1024)
        val zramUsedMb: Long get() = zramUsedKb / 1024
        val zramMemUsedMb: Long get() = zramMemUsedBytes / (1024 * 1024)
        val zramCompression: Double? get() = if (zramComprBytes > 0 && zramOrigBytes > 0) {
            zramOrigBytes.toDouble() / zramComprBytes
        } else null
    }

    /** Reads + logs every memory parameter (see MeasureLog). */
    fun readAndLog(): Snapshot? {
        val snapshot = read() ?: return null
        MeasureLog.sample("mem.used", snapshot.usedPercent, "%", snapshot.source)
        MeasureLog.sample("mem.available", snapshot.memAvailableKb / 1024, "MB", snapshot.source)
        MeasureLog.sample("swap.used", snapshot.swapUsedKb / 1024, "MB", "proc/swaps")
        MeasureLog.sample("zram.uncompressed", snapshot.zramUsedMb, "MB", "proc/swaps")
        MeasureLog.sample("zram.physical", snapshot.zramMemUsedMb, "MB", "mm_stat")
        MeasureLog.sample(
            "zram.ratio",
            snapshot.zramCompression?.let { String.format(java.util.Locale.US, "%.2f", it) },
            "x",
            "mm_stat"
        )
        return snapshot
    }

    fun read(): Snapshot? {
        val values = SysReader.read(
            listOf(MEMINFO_PATH, MM_STAT_PATH, SWAPS_PATH, "/sys/block/zram0/disksize")
        )
        val mem = values[MEMINFO_PATH]?.let { parseMemInfo(it) } ?: return null
        val mmStat = values[MM_STAT_PATH]?.let { parseMmStat(it) }
        val zramUsedKb = values[SWAPS_PATH]?.let { parseZramUsedKb(it) } ?: 0L

        return Snapshot(
            memTotalKb = mem["MemTotal"] ?: 0L,
            memAvailableKb = mem["MemAvailable"] ?: mem["MemFree"] ?: 0L,
            memFreeKb = mem["MemFree"] ?: 0L,
            cachedKb = mem["Cached"] ?: 0L,
            swapCachedKb = mem["SwapCached"] ?: 0L,
            dirtyKb = mem["Dirty"] ?: 0L,
            buffersKb = mem["Buffers"] ?: 0L,
            swapTotalKb = mem["SwapTotal"] ?: 0L,
            swapFreeKb = mem["SwapFree"] ?: 0L,
            zramDisksizeBytes = values["/sys/block/zram0/disksize"]?.toLongOrNull() ?: 0L,
            zramOrigBytes = mmStat?.getOrNull(0) ?: 0L,
            zramComprBytes = mmStat?.getOrNull(1) ?: 0L,
            zramMemUsedBytes = mmStat?.getOrNull(2) ?: 0L,
            zramUsedKb = zramUsedKb,
            source = if (values.containsKey(MEMINFO_PATH)) "meminfo" else "unknown"
        )
    }

    // ------------------------------------------------------------- parsers
    /** `/proc/meminfo` key -> kB. Pure. */
    fun parseMemInfo(text: String): Map<String, Long> = text.lines().mapNotNull { line ->
        val idx = line.indexOf(':')
        if (idx <= 0) null else {
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull()
            if (value == null) null else key to value
        }
    }.toMap()

    /**
     * zram `mm_stat` fields:
     * orig_data_size, compr_data_size, mem_used_total, mem_limit, mem_used_max, ...
     * Pure.
     */
    fun parseMmStat(text: String): List<Long> = text.lineSequence().firstOrNull()
        ?.trim()?.split(Regex("\\s+"))
        ?.mapNotNull { it.toLongOrNull() }
        ?: emptyList()

    /** Used KB of the zram swap row in `/proc/swaps`. Pure. */
    fun parseZramUsedKb(text: String): Long {
        for (line in text.lines()) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 4 && parts[0].endsWith("zram0")) {
                return parts[3].toLongOrNull() ?: 0L
            }
        }
        return 0L
    }
}
