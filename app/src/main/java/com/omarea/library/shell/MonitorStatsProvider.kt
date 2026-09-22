package com.omarea.library.shell

import com.omarea.common.shell.ShellCapability
import com.omarea.common.shell.ShellCapabilityRegistry

/**
 * Chooses the best available data source for the live monitor widgets.
 *
 * The monitor has two families of sources:
 *
 * - **sysfs / kernel** - fast and precise, but the GPU (`kgsl`), battery power supply and devfreq
 *   nodes are root-only on the target device.
 * - **framework** (`dumpsys`) - slower but reachable at shell uid, so it keeps the monitor
 *   populated in a Shizuku session instead of showing blanks.
 *
 * Widgets ask this provider instead of calling `GpuUtils` / `BatteryUtils` directly, so the
 * degradation is decided in one place based on the probed [ShellCapability] set.
 */
object MonitorStatsProvider {

    /**
     * GPU load in percent.
     *
     * Uses the kgsl counters when they are readable. Otherwise falls back to janky-frame ratio
     * from `dumpsys gfxinfo`, which is a different but still meaningful "how well is the GPU
     * keeping up" signal. Returns -1 when neither source is available.
     *
     * @param preferSysfs set to false from background workers to avoid a dumpsys round trip when
     *   the user only wants the cheap sysfs value.
     */
    fun gpuLoadPercent(preferSysfs: Boolean = true): Int {
        if (ShellCapabilityRegistry.supports(ShellCapability.GPU_SYSFS_READ)) {
            val sysfsLoad = GpuUtils.getGpuLoad()
            if (sysfsLoad >= 0) {
                return sysfsLoad
            }
        }
        if (!preferSysfs) {
            return -1
        }
        if (!ShellCapabilityRegistry.supports(ShellCapability.DUMPSYS)) {
            return -1
        }
        val stats = FrameworkStats.gpuFrameStats() ?: return -1
        val jank = stats.jankPercent
        return if (jank < 0f) -1 else jank.toInt().coerceIn(0, 100)
    }

    /**
     * GPU frequency in MHz as a display string.
     *
     * Falls back to the frame statistics reported by `dumpsys gfxinfo` when kgsl is not readable;
     * that value is a clock limit rather than the instantaneous clock, so it is suffixed with "*"
     * to make the difference visible instead of pretending it is the live frequency.
     */
    fun gpuFrequencyMHz(): String {
        if (ShellCapabilityRegistry.supports(ShellCapability.GPU_SYSFS_READ)) {
            val freq = GpuUtils.getGpuFreq()
            if (freq.isNotEmpty()) {
                return freq
            }
        }
        if (!ShellCapabilityRegistry.supports(ShellCapability.DUMPSYS)) {
            return ""
        }
        // Reuse the same gfxinfo read as the load path so the monitor makes one shell call per tick,
        // and a denied/absent source is remembered instead of being retried every second.
        val maxClock = FrameworkStats.gpuMaxClockMHz() ?: return ""
        return "$maxClock*"
    }

    /** GPU memory usage, or null when unavailable in the current tier. */
    fun gpuMemoryUsage(): String? {
        return FrameworkStats.gpuMemoryUsage()
    }

    /**
     * Battery current in microamperes.
     *
     * [android.os.BatteryManager] properties already work without root, so this only falls back to
     * parsing `dumpsys battery` when the property is unavailable (some ROMs return 0).
     */
    fun batteryCurrentMicroAmps(frameworkValue: Long?): Long? {
        if (frameworkValue != null && frameworkValue != 0L) {
            return frameworkValue
        }
        if (!ShellCapabilityRegistry.supports(ShellCapability.DUMPSYS)) {
            return frameworkValue
        }
        val output = com.omarea.common.shell.KeepShellPublic.doCmdSync("dumpsys battery")
        // `dumpsys battery` reports the instantaneous current in microamperes on some ROMs only.
        val match = Regex("(?im)^\\s*current now:\\s*(-?\\d+)").find(output) ?: return frameworkValue
        return match.groupValues[1].toLongOrNull() ?: frameworkValue
    }

    /** True when the monitor should explain that some values come from a fallback source. */
    fun usingFallbackSources(): Boolean {
        return !ShellCapabilityRegistry.supports(ShellCapability.GPU_SYSFS_READ) &&
                ShellCapabilityRegistry.supports(ShellCapability.DUMPSYS)
    }

    /**
     * True when this session can show meaningful live monitoring.
     *
     * CPU load, frequency, per-core load, thermal zones and memory are readable at every tier, so
     * only a session that cannot even reach the framework is truly unable to monitor. The UI uses
     * this instead of `isPrivileged` to decide whether monitoring screens are worth keeping open.
     */
    fun canMonitor(): Boolean {
        return ShellCapabilityRegistry.supports(ShellCapability.PROC_READ) ||
                ShellCapabilityRegistry.supports(ShellCapability.CPU_SYSFS_READ) ||
                ShellCapabilityRegistry.supports(ShellCapability.DUMPSYS)
    }

    /**
     * Describes which sources the current session is using, for display in the monitor header.
     * Returns an empty string when everything comes from the kernel directly.
     */
    fun sourceSummary(): String {
        val parts = ArrayList<String>()
        if (ShellCapabilityRegistry.supports(ShellCapability.CPU_SYSFS_READ)) {
            parts.add("cpu:sysfs")
        } else {
            parts.add("cpu:proc")
        }
        parts.add(if (ShellCapabilityRegistry.supports(ShellCapability.GPU_SYSFS_READ)) "gpu:kgsl" else "gpu:gfxinfo")
        parts.add(if (ShellCapabilityRegistry.supports(ShellCapability.BATTERY_SYSFS_READ)) "batt:sysfs" else "batt:framework")
        return parts.joinToString(" ")
    }
}
