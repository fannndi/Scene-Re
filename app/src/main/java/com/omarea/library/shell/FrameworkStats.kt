package com.omarea.library.shell

import android.util.Log
import com.omarea.common.shell.KeepShellPublic

/**
 * Reads CPU, GPU and memory statistics through the Android framework instead of sysfs.
 *
 * Why this exists: on a Shizuku (uid 2000) session every sysfs write and several reads
 * (`/sys/class/kgsl`, `/sys/class/devfreq`, battery power supply nodes) are denied, so a monitor
 * built purely on sysfs shows nothing. The framework, however, is fully reachable through
 * `dumpsys` at shell uid. This helper implements that fallback path so monitoring still works
 * without root.
 *
 * Sources used:
 * - CPU total load: `/proc/stat` deltas (readable at any tier).
 * - Per-process CPU: `dumpsys cpuinfo`.
 * - Process memory and RAM totals: `dumpsys meminfo`.
 * - Foreground package: `dumpsys activity activities`.
 * - GPU frame timing: `dumpsys gfxinfo` for the foreground package, which is the reachable
 *   stand-in for the root-only kgsl GPU load counters.
 *
 * Parsing lives in [FrameworkStatsParser] so it can be unit tested without a device. This class
 * only runs the shell commands and logs each failure once to keep logcat readable.
 */
object FrameworkStats {
    private const val TAG = "SceneFrameworkStats"

    /** How long a `dumpsys gfxinfo` result stays usable, in milliseconds. */
    private const val GFX_CACHE_MS = 2000L

    /** Tracks whether a warning was already logged for a source, to keep logcat clean. */
    private val warnedSources = HashSet<String>()

    /**
     * Sources that already failed and must not be retried on every refresh tick.
     *
     * The monitor polls once a second. Retrying a source the kernel refuses produces an SELinux
     * audit line per attempt, which floods the log and burns battery for no result. A source is
     * only retried after [resetWarnings], which the privilege tier change calls.
     */
    private val disabledSources = HashSet<String>()

    private fun warnOnce(source: String, message: String) {
        synchronized(warnedSources) {
            if (warnedSources.add(source)) {
                Log.w(TAG, "$source: $message")
            }
        }
    }

    /** Marks a source as permanently unavailable until the privilege tier changes. */
    private fun disableSource(source: String) {
        synchronized(disabledSources) {
            disabledSources.add(source)
        }
    }

    private fun isDisabled(source: String): Boolean {
        synchronized(disabledSources) {
            return disabledSources.contains(source)
        }
    }

    /** Resets the once-only warning state; used when the privilege tier changes. */
    fun resetWarnings() {
        synchronized(warnedSources) {
            warnedSources.clear()
        }
        synchronized(disabledSources) {
            disabledSources.clear()
        }
        synchronized(gfxCache) {
            gfxCache.clear()
        }
    }

    private fun dump(command: String, source: String): String {
        if (isDisabled(source)) {
            return ""
        }
        val output = KeepShellPublic.doCmdSync(command)
        if (output.isBlank() || output == "error") {
            warnOnce(source, "no output from '$command'")
            disableSource(source)
            return ""
        }
        return output
    }

    /** Reads the aggregate `/proc/stat` cpu line. */
    fun readCpuTicks(): FrameworkStatsParser.CpuTicks? {
        val line = dump("head -1 /proc/stat", "procstat")
        if (line.isEmpty()) {
            return null
        }
        val ticks = FrameworkStatsParser.parseCpuTicks(line)
        if (ticks == null) {
            warnOnce("procstat", "unexpected format: $line")
            disableSource("procstat")
        }
        return ticks
    }

    /** Total CPU load in percent, or -1 when there is not yet a usable baseline. */
    fun cpuLoadPercent(previous: FrameworkStatsParser.CpuTicks?): Pair<Int, FrameworkStatsParser.CpuTicks?> {
        val current = readCpuTicks() ?: return Pair(-1, previous)
        if (previous == null) {
            return Pair(-1, current)
        }
        return Pair(FrameworkStatsParser.cpuLoadPercent(previous, current), current)
    }

    /**
     * Total CPU load in percent using whatever source is reachable.
     *
     * `/proc/stat` is the cheap source and works whenever the app's own shell can read it, but the
     * app's uid is denied that path on some ROMs. `dumpsys cpuinfo` reports a whole-device total
     * instead and stays reachable at shell uid, so it is used as the fallback. Returns -1 when
     * neither source produced a number.
     *
     * State is kept per source because the two report on different bases; mixing a `/proc/stat`
     * sample with a `dumpsys` sample would produce a meaningless delta.
     */
    fun totalCpuLoadPercent(): Int {
        val ticks = readCpuTicks()
        if (ticks != null) {
            val previous = lastProcTicks
            lastProcTicks = ticks
            if (previous != null) {
                val percent = FrameworkStatsParser.cpuLoadPercent(previous, ticks)
                if (percent >= 0) {
                    return percent
                }
            }
            return -1
        }
        val output = dump("dumpsys cpuinfo", "cpuinfo_total")
        if (output.isEmpty()) {
            return -1
        }
        return FrameworkStatsParser.parseTotalCpuLoad(output) ?: -1
    }

    /** Previous `/proc/stat` sample, used only by [totalCpuLoadPercent]. */
    @Volatile
    private var lastProcTicks: FrameworkStatsParser.CpuTicks? = null

    /** Top CPU consumers from `dumpsys cpuinfo`. */
    fun topCpuConsumers(limit: Int = 10): List<FrameworkStatsParser.CpuInfoEntry> {
        val output = dump("dumpsys cpuinfo", "cpuinfo")
        if (output.isEmpty()) {
            return emptyList()
        }
        val entries = FrameworkStatsParser.parseCpuInfo(output, limit)
        if (entries.isEmpty()) {
            warnOnce("cpuinfo", "no parsable rows")
            disableSource("cpuinfo")
        }
        return entries
    }

    /** Current foreground package, or null when it cannot be determined. */
    fun currentForegroundPackage(): String? {
        val output = dump("dumpsys activity activities", "activity")
        if (output.isEmpty()) {
            return null
        }
        val pkg = FrameworkStatsParser.parseForegroundPackage(output)
        if (pkg == null) {
            warnOnce("activity", "no foreground package found")
        }
        return pkg
    }

    /**
     * Frame statistics for a package (defaults to the foreground package).
     *
     * The raw `gfxinfo` output is cached per package for a short window: the monitor asks for load,
     * clock and memory on the same tick, and running the same `dumpsys` three times per second is
     * wasteful. [resetWarnings] clears the cache when the tier changes.
     */
    fun gpuFrameStats(packageName: String? = null): FrameworkStatsParser.GpuFrameStats? {
        if (isDisabled("gfxinfo")) {
            return null
        }
        val target = packageName ?: currentForegroundPackage()
        if (target.isNullOrEmpty()) {
            return null
        }
        val stats = FrameworkStatsParser.parseGpuFrameStats(target, rawGfxInfo(target))
        if (stats == null) {
            warnOnce("gfxinfo", "no frame stats for $target")
        }
        return stats
    }

    /**
     * GPU clock ceiling in MHz from `dumpsys gfxinfo`, or null when it is not reported.
     *
     * This is a limit, not the live clock - the widget marks it to avoid implying otherwise.
     */
    fun gpuMaxClockMHz(): Int? {
        if (isDisabled("gfxinfo")) {
            return null
        }
        val target = currentForegroundPackage() ?: return null
        val match = MAX_CLOCK_REGEX.find(rawGfxInfo(target)) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    /**
     * Total GPU memory in bytes from `dumpsys gfxinfo`, or null when it is not reported.
     */
    fun gpuTotalMemoryBytes(): Long? {
        if (isDisabled("gfxinfo")) {
            return null
        }
        val target = currentForegroundPackage() ?: return null
        val match = GPU_MEMORY_REGEX.find(rawGfxInfo(target)) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    private val MAX_CLOCK_REGEX = Regex("Max clock frequency:\\s*(\\d+)\\s*MHz")
    private val GPU_MEMORY_REGEX = Regex("Total GPU memory usage:\\s*(\\d+)")

    private val gfxCache = HashMap<String, Pair<String, Long>>()

    /** Runs `dumpsys gfxinfo` for a package, reusing a very recent result. */
    private fun rawGfxInfo(packageName: String): String {
        synchronized(gfxCache) {
            val hit = gfxCache[packageName]
            if (hit != null && System.currentTimeMillis() - hit.second < GFX_CACHE_MS) {
                return hit.first
            }
        }
        val output = dump("dumpsys gfxinfo $packageName", "gfxinfo")
        synchronized(gfxCache) {
            if (output.isEmpty()) {
                gfxCache.remove(packageName)
            } else {
                gfxCache[packageName] = Pair(output, System.currentTimeMillis())
            }
        }
        return output
    }

    /** Per-process memory (PSS in kilobytes) from `dumpsys meminfo`. */
    fun processMemoryMap(): Map<String, Long> {
        val output = dump("dumpsys meminfo", "meminfo")
        if (output.isEmpty()) {
            return emptyMap()
        }
        return FrameworkStatsParser.parseProcessMemory(output)
    }

    /** Total / free / used / lost RAM in kilobytes from `dumpsys meminfo`. */
    fun ramTotals(): FrameworkStatsParser.RamTotals? {
        val output = dump("dumpsys meminfo", "meminfo")
        if (output.isEmpty()) {
            return null
        }
        return FrameworkStatsParser.parseRamTotals(output)
    }

    /**
     * GPU memory usage as a display string.
     *
     * Prefers the kernel kgsl counter (readable only with root) and falls back to the driver
     * accounting printed by `dumpsys gfxinfo`, which is available at shell uid.
     */
    fun gpuMemoryUsage(): String? {
        val sysfs = GpuUtils.getMemoryUsage()
        if (!sysfs.isNullOrEmpty()) {
            return sysfs
        }
        val bytes = gpuTotalMemoryBytes() ?: return null
        return (bytes / 1024 / 1024).toString() + "MB"
    }
}
