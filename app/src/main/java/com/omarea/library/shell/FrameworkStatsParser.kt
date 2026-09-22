package com.omarea.library.shell

/**
 * Pure parsers for the text output of Android framework diagnostics (`dumpsys`, `/proc/stat`).
 *
 * These are separated from [FrameworkStats] so they can be unit tested on the JVM against real
 * dumps captured from a device: the formats vary subtly between ROM versions and a regex that is
 * only ever exercised on a phone is a regex that breaks silently.
 *
 * Every parser returns null or an empty result on unparsable input; none of them throw.
 */
object FrameworkStatsParser {

    /** One `/proc/stat` cpu line, in jiffies. */
    data class CpuTicks(val total: Long, val idle: Long)

    /** Per-process CPU usage parsed from `dumpsys cpuinfo`. */
    data class CpuInfoEntry(val packageName: String, val cpuPercent: Float)

    /** GPU frame statistics parsed from `dumpsys gfxinfo`. */
    data class GpuFrameStats(
        val packageName: String,
        val totalFrames: Int,
        val jankyFrames: Int,
        val percentile90Ms: Float,
        val percentile95Ms: Float,
        val percentile99Ms: Float
    ) {
        /** Janky frame ratio in percent; -1 when there were no frames. */
        val jankPercent: Float
            get() = if (totalFrames <= 0) -1f else (jankyFrames * 100f / totalFrames)
    }

    /**
     * Parses the aggregate `cpu` line of `/proc/stat`.
     *
     * Format: `cpu user nice system idle iowait irq softirq steal guest guest_nice`.
     * `idle` is idle + iowait; everything else counts as busy.
     */
    fun parseCpuTicks(line: String): CpuTicks? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 5 || !parts[0].startsWith("cpu")) {
            return null
        }
        val values = parts.drop(1).mapNotNull { it.toLongOrNull() }
        if (values.size < 4) {
            return null
        }
        val idle = values.getOrElse(3) { 0L } + values.getOrElse(4) { 0L }
        val total = values.sum()
        return if (total <= 0L) null else CpuTicks(total, idle)
    }

    /**
     * Computes the CPU load percentage between two ticks samples.
     * Returns -1 when the delta cannot produce a meaningful ratio.
     */
    fun cpuLoadPercent(previous: CpuTicks?, current: CpuTicks): Int {
        if (previous == null) {
            return -1
        }
        val totalDelta = current.total - previous.total
        val idleDelta = current.idle - previous.idle
        if (totalDelta <= 0L) {
            return -1
        }
        val busy = totalDelta - idleDelta
        return ((busy * 100L) / totalDelta).toInt().coerceIn(0, 100)
    }

    /**
     * Parses the rows of `dumpsys cpuinfo`.
     *
     * Expected lines look like `  3.4% 1234/com.example.app: 1.2% user + 2.2% kernel`.
     * Kernel threads have no package component (`0.9% 28519/kworker/u16:3:`) and are kept as-is.
     */
    fun parseCpuInfo(output: String, limit: Int = 10): List<CpuInfoEntry> {
        val entries = ArrayList<CpuInfoEntry>()
        val lineRegex = Regex("^\\s*([0-9.]+)%\\s+(\\S+)")
        for (line in output.lines()) {
            val match = lineRegex.find(line) ?: continue
            val percent = match.groupValues[1].toFloatOrNull() ?: continue
            val rawName = match.groupValues[2]
            // The process field is "pid/package"; strip the pid and any trailing colon.
            val name = rawName.substringAfter('/', rawName).removeSuffix(":")
            if (name.isEmpty() || name.startsWith("TOTAL")) continue
            entries.add(CpuInfoEntry(name, percent))
            if (entries.size >= limit) break
        }
        return entries
    }

    /**
     * Parses the whole-device CPU total from the `TOTAL:` line of `dumpsys cpuinfo`.
     *
     * Used as the fallback for the `/proc/stat` delta when the app's uid is denied that file. The
     * line looks like `7.3% TOTAL: 4.4% user + 2.2% kernel + 0.2% iowait`, and the components are
     * already percentages of the whole device, so they are summed directly.
     *
     * Note the leading figure (`7.3%`) is a separate, rounded total that appears *before* the
     * `TOTAL:` label, so the line cannot be matched with a `^\s*TOTAL:` anchor. The components are
     * summed instead of trusting that leading figure, which is more precise and works regardless of
     * whether the ROM prints it.
     *
     * Returns null when the dump has no `TOTAL:` line, which happens on ROMs that trim it.
     */
    fun parseTotalCpuLoad(output: String): Int? {
        // Search anywhere on the line: the label is preceded by the rounded total percentage.
        val totalRegex = Regex("TOTAL:\\s*(.*)$")
        for (line in output.lines()) {
            val body = totalRegex.find(line)?.groupValues?.get(1) ?: continue
            // Sum every "N% <label>" component: user, kernel, iowait, irq, softirq, guest.
            var sum = 0f
            var found = false
            for (match in Regex("([0-9.]+)%").findAll(body)) {
                val value = match.groupValues[1].toFloatOrNull() ?: continue
                sum += value
                found = true
            }
            if (!found) {
                return null
            }
            return sum.toInt().coerceIn(0, 100)
        }
        return null
    }

    /**
     * Parses frame timing from `dumpsys gfxinfo <package>`.
     *
     * Returns null when the dump contains no frame statistics, which is the case for packages
     * that have not rendered anything since the counters were reset.
     */
    fun parseGpuFrameStats(packageName: String, output: String): GpuFrameStats? {
        if (!output.contains("Total frames rendered")) {
            return null
        }
        var totalFrames = 0
        var jankyFrames = 0
        var p90 = 0f
        var p95 = 0f
        var p99 = 0f
        for (line in output.lines()) {
            val trimmed = line.trim()
            // "Janky frames (legacy):" also starts with "Janky frames", so it must be matched
            // first and skipped, otherwise it would overwrite the real count.
            if (trimmed.startsWith("Janky frames (legacy):")) {
                continue
            }
            when {
                trimmed.startsWith("Total frames rendered:") ->
                    totalFrames = trimmed.substringAfter(':').trim().toIntOrNull() ?: totalFrames
                trimmed.startsWith("Janky frames:") -> {
                    // Format: "Janky frames: 123 (4.56%)"
                    val value = trimmed.substringAfter(':').trim().substringBefore(' ').trim()
                    jankyFrames = value.toIntOrNull() ?: jankyFrames
                }
                // Only the top-level percentiles matter; "90th gpu percentile" is a different metric,
                // so the prefix must not be a loose contains() check.
                trimmed.startsWith("90th percentile:") ->
                    p90 = parseMillis(trimmed.substringAfter(':').trim()) ?: p90
                trimmed.startsWith("95th percentile:") ->
                    p95 = parseMillis(trimmed.substringAfter(':').trim()) ?: p95
                trimmed.startsWith("99th percentile:") ->
                    p99 = parseMillis(trimmed.substringAfter(':').trim()) ?: p99
            }
        }
        return GpuFrameStats(packageName, totalFrames, jankyFrames, p90, p95, p99)
    }

    private fun parseMillis(value: String): Float? {
        return value.removeSuffix("ms").trim().toFloatOrNull()
    }

    /**
     * Parses the per-process table of `dumpsys meminfo`.
     *
     * Row formats handled:
     * ```
     *   123,456K: com.example.app (pid 1234)
     *   281,452K: com.android.settings (pid 29956 / activities)
     * ```
     * The size is returned in kilobytes, keyed by process name.
     */
    fun parseProcessMemory(output: String): Map<String, Long> {
        val map = LinkedHashMap<String, Long>()
        val rowRegex = Regex("^\\s*([0-9,]+)K:\\s+(\\S+)\\s+\\(pid\\s+\\d+(?:\\s*/\\s*\\S+)?\\)")
        for (line in output.lines()) {
            val match = rowRegex.find(line) ?: continue
            val kb = match.groupValues[1].replace(",", "").toLongOrNull() ?: continue
            map[match.groupValues[2]] = kb
        }
        return map
    }

    /** Total / free / used RAM in kilobytes from `dumpsys meminfo`. */
    data class RamTotals(val totalKb: Long, val freeKb: Long, val usedKb: Long, val lostKb: Long)

    fun parseRamTotals(output: String): RamTotals? {
        var total = -1L
        var free = -1L
        var used = -1L
        var lost = -1L
        for (line in output.lines()) {
            val trimmed = line.trim()
            val kb = extractFirstKb(trimmed)
            when {
                trimmed.startsWith("Total RAM:") -> total = kb ?: total
                trimmed.startsWith("Free RAM:") -> free = kb ?: free
                trimmed.startsWith("Used RAM:") -> used = kb ?: used
                trimmed.startsWith("Lost RAM:") -> lost = kb ?: lost
            }
        }
        if (total < 0) {
            return null
        }
        return RamTotals(total, free, used, lost)
    }

    /** Pulls the first `<number>K` value out of a line, tolerating thousands separators. */
    private fun extractFirstKb(line: String): Long? {
        val match = Regex("([0-9,]+)K").find(line) ?: return null
        return match.groupValues[1].replace(",", "").toLongOrNull()
    }

    /**
     * Extracts the foreground package name from `dumpsys activity activities`.
     *
     * Prefers the resumed-activity and focused-app fields, which name the top package most
     * reliably; avoids needing an accessibility service just to know what is on screen.
     */
    fun parseForegroundPackage(output: String): String? {
        val patterns = listOf(
            Regex("mResumedActivity.*?\\s([A-Za-z0-9_.]+)/"),
            Regex("mFocusedApp.*?\\s([A-Za-z0-9_.]+)/"),
            Regex("topResumedActivity.*?\\s([A-Za-z0-9_.]+)/")
        )
        for (pattern in patterns) {
            val match = pattern.find(output)
            if (match != null) {
                val pkg = match.groupValues[1]
                if (pkg.isNotEmpty() && pkg.contains('.')) {
                    return pkg
                }
            }
        }
        return null
    }
}
