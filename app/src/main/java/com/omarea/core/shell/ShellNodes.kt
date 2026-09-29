package com.omarea.core.shell

/**
 * Central registry of every kernel node and runtime file used by the
 * profile engine and its controllers.
 *
 * Responsibility: name kernel/runtime paths exactly once.
 * Non-goals: talking to the shell (see [RootShell]) or props (see [PropShell]).
 * Invariants:
 *  - Paths are device-independent names; per-device differences live in the
 *    tuning JSON, not here.
 *  - Runtime files are shared with the scene_thermald shell daemon.
 */
object ShellNodes {
    const val CPU = "/sys/devices/system/cpu"
    const val SCHED = "/proc/sys/kernel"
    const val STUNE = "/dev/stune"
    const val CPUSET = "/dev/cpuset"
    const val VM = "/proc/sys/vm"
    const val MSM_PERFORMANCE = "/sys/module/msm_performance/parameters"
    const val CPU_BOOST = "/sys/module/cpu_boost/parameters"
    const val LPM_SLEEP_DISABLED = "/sys/module/lpm_levels/parameters/sleep_disabled"
    const val READ_AHEAD_KB = "/sys/block/sda/queue/read_ahead_kb"
    const val THERMAL_SCONFIG = "/sys/class/thermal/thermal_message/sconfig"
    const val GPU = "/sys/class/kgsl/kgsl-3d0"
    const val UFS = "/sys/devices/platform/soc/1d84000.ufshc"
    const val UFS_DEVFREQ = "/sys/class/devfreq/1d84000.ufshc"

    // Runtime files shared with the scene_thermald daemon.
    const val THERMALD_SCRIPT = "/data/local/tmp/scene_thermald.sh"
    const val THERMALD_PROFILE_MAX = "/data/local/tmp/scene_thermald.profile_max"
    const val THERMALD_STATE = "/data/local/tmp/scene_thermald.state"
    const val THERMALD_STOP = "/data/local/tmp/scene_thermald.stop"

    fun cpufreq(policy: String) = "$CPU/cpufreq/$policy"
    fun cpuNode(cpu: String, leaf: String) = "$CPU/cpu$cpu/$leaf"
    fun coreCtl(cpu: String) = cpuNode(cpu, "core_ctl")
    fun sched(name: String) = "$SCHED/$name"
}
