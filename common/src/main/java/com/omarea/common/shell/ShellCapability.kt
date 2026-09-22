package com.omarea.common.shell

/**
 * Capability tokens for operations that depend on the active privilege tier.
 *
 * The shell layer does not guess whether a feature works: every capability is either
 * probed at runtime (see [ShellCapabilityProbe]) or derived from a known tier rule.
 * UI code must consult these tokens instead of checking for root directly, so that a
 * Shizuku or non-root session degrades gracefully instead of failing silently.
 */
enum class ShellCapability(val id: String) {
    /** Read the CPU cpufreq and core_ctl nodes under `/sys/devices/system/cpu`. */
    CPU_SYSFS_READ("cpu_sysfs_read"),

    /** Write CPU frequency limits, governors and core control under `/sys/devices/system/cpu`. */
    CPU_SYSFS_WRITE("cpu_sysfs_write"),

    /** Read the Adreno/kgsl GPU nodes. Denied for shell uid on surya. */
    GPU_SYSFS_READ("gpu_sysfs_read"),
    /** Write the Adreno/kgsl GPU nodes. Root only. */
    GPU_SYSFS_WRITE("gpu_sysfs_write"),

    /** Read `/sys/class/thermal/thermal_zone*`. */
    THERMAL_SYSFS_READ("thermal_sysfs_read"),

    /** Write thermal controls. Root only on surya. */
    THERMAL_SYSFS_WRITE("thermal_sysfs_write"),

    /** Read `/proc/meminfo`, `/proc/stat`. */
    PROC_READ("proc_read"),

    /** Read battery power supply nodes. Denied for shell uid on surya. */
    BATTERY_SYSFS_READ("battery_sysfs_read"),

    /** Read battery data through the Android framework (`BatteryManager`, `dumpsys`). */
    BATTERY_FRAMEWORK_READ("battery_framework_read"),

    /** Query the framework through `dumpsys` (meminfo, gfxinfo, batterystats). */
    DUMPSYS("dumpsys"),

    /** Use `cmd` / `am` / `pm` to control packages, appops and users. */
    FRAMEWORK_CONTROL("framework_control"),

    /** Freeze, suspend, disable or force-stop packages. */
    APP_CONTROL("app_control"),

    /**
     * Read and write the `settings` providers.
     *
     * This is a framework facility (`settings get`/`settings put`), not a sysfs node, so the shell
     * uid can use it. Note that the probe verifies the command is reachable; a `settings put` on a
     * protected namespace is still refused by the framework for a non-root caller.
     */
    SETTINGS_WRITE("settings_write"),

    /** Read and write sysfs nodes owned by kernel modules (cpu_boost, msm_performance). */
    KERNEL_MODULE_RW("kernel_module_rw"),

    /** Block-device and storage tuning (`read_ahead_kb`, UFS nodes, devfreq). */
    STORAGE_TUNING("storage_tuning");

    companion object {
        fun fromId(id: String?): ShellCapability? {
            return values().firstOrNull { it.id == id }
        }
    }
}

/**
 * Result of a capability probe.
 *
 * @param capability the probed token.
 * @param available whether the operation succeeded at probe time.
 * @param detail short human-readable evidence, e.g. the path that was denied.
 */
data class CapabilityResult(
    val capability: ShellCapability,
    val available: Boolean,
    val detail: String = ""
)

/**
 * Snapshot of everything that was probed. Immutable so it can be passed to the UI safely.
 */
data class CapabilitySnapshot(
    val tier: String,
    val results: Map<ShellCapability, CapabilityResult>,
    val probedAtMillis: Long
) {
    fun available(capability: ShellCapability): Boolean {
        return results[capability]?.available == true
    }

    fun detail(capability: ShellCapability): String {
        return results[capability]?.detail ?: ""
    }

    companion object {
        val EMPTY = CapabilitySnapshot("unknown", emptyMap(), 0L)
    }
}

/**
 * Runtime probe for [ShellCapability]. The app module registers an implementation, because only
 * it knows about the privilege tier; the common shell layer never depends on app code.
 *
 * Implementations must never write to system state while probing - use read-only checks.
 */
interface ShellCapabilityProbe {
    /** Returns the cached snapshot, or [CapabilitySnapshot.EMPTY] before the first probe. */
    fun snapshot(): CapabilitySnapshot

    /** Re-probes every capability and returns the fresh snapshot. May block; call off the main thread. */
    fun probe(): CapabilitySnapshot
}

/**
 * Global registry for the capability probe.
 *
 * Like [ShellModeProvider], this is a single process-wide slot so that shared code in `:krscript`
 * and `:common` can ask about capabilities without knowing the app module.
 */
object ShellCapabilityRegistry {
    @Volatile
    var probe: ShellCapabilityProbe? = null

    /** Convenience accessor used by feature code; returns an empty snapshot when unregistered. */
    fun snapshot(): CapabilitySnapshot {
        return probe?.snapshot() ?: CapabilitySnapshot.EMPTY
    }

    fun supports(capability: ShellCapability): Boolean {
        return snapshot().available(capability)
    }
}
