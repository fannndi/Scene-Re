package com.omarea.library.shell

import com.omarea.common.shell.KeepShellPublic

/**
 * Kernel parameter registry, adopted from RvKernel-Manager (GPL-3.0, Rve27).
 *
 * RvKernel-Manager uses libsu for root; here every read/write goes through the app's own shell
 * backend ([KeepShellPublic]), so the registry follows the active privilege tier (root or Shizuku)
 * automatically and the UI can show which parameters actually exist on this kernel.
 */
enum class KernelParamType { BOOL, INT, TEXT, READ_ONLY }

data class KernelParameter(
    val path: String,
    val label: String,
    val type: KernelParamType = KernelParamType.TEXT
)

object KernelParameters {
    val all: List<KernelParameter> = listOf(
        // Scheduler
        KernelParameter("/proc/sys/kernel/sched_autogroup_enabled", "Sched autogroup", KernelParamType.BOOL),
        KernelParameter("/proc/sys/kernel/sched_util_clamp_min", "Util clamp min", KernelParamType.INT),
        KernelParameter("/proc/sys/kernel/sched_util_clamp_max", "Util clamp max", KernelParamType.INT),
        KernelParameter("/proc/sys/kernel/sched_util_clamp_min_rt_default", "Util clamp RT default", KernelParamType.INT),
        // BORE / burst scheduler (custom kernels)
        KernelParameter("/proc/sys/kernel/sched_bore", "BORE scheduler", KernelParamType.BOOL),
        KernelParameter("/proc/sys/kernel/sched_burst_smoothness_long", "Burst smoothness long", KernelParamType.INT),
        KernelParameter("/proc/sys/kernel/sched_burst_smoothness_short", "Burst smoothness short", KernelParamType.INT),
        KernelParameter("/proc/sys/kernel/sched_burst_fork_atavistic", "Burst fork atavistic", KernelParamType.INT),
        KernelParameter("/proc/sys/kernel/sched_burst_penalty_offset", "Burst penalty offset", KernelParamType.INT),
        KernelParameter("/proc/sys/kernel/sched_burst_penalty_scale", "Burst penalty scale", KernelParamType.INT),
        KernelParameter("/proc/sys/kernel/sched_burst_cache_lifetime", "Burst cache lifetime", KernelParamType.INT),
        // Debug / logging
        KernelParameter("/proc/sys/kernel/printk", "Printk level", KernelParamType.TEXT),
        KernelParameter("/proc/sys/kernel/dmesg_restrict", "dmesg restrict", KernelParamType.BOOL),
        // Memory
        KernelParameter("/proc/sys/vm/swappiness", "Swappiness", KernelParamType.INT),
        KernelParameter("/proc/sys/vm/dirty_ratio", "Dirty ratio", KernelParamType.INT),
        // Network
        KernelParameter("/proc/sys/net/ipv4/tcp_congestion_control", "TCP congestion", KernelParamType.TEXT),
        KernelParameter("/proc/sys/net/ipv4/tcp_available_congestion_control", "TCP available", KernelParamType.READ_ONLY),
        KernelParameter("/sys/module/wireguard/version", "WireGuard", KernelParamType.READ_ONLY)
    )

    /**
     * Reads every parameter in one shell round trip.
     *
     * @return path -> current value; paths that do not exist on this kernel are absent.
     */
    fun readAll(): Map<String, String> {
        val script = StringBuilder()
        for (parameter in all) {
            script.append("echo \"${parameter.path}=\$(cat ${parameter.path} 2>/dev/null)\"\n")
        }
        val output = KeepShellPublic.doCmdSync(script.toString())
        val values = HashMap<String, String>()
        for (line in output.lines()) {
            val index = line.indexOf('=')
            if (index > 0) {
                val path = line.substring(0, index)
                val value = line.substring(index + 1).trim()
                if (value.isNotEmpty()) {
                    values[path] = value
                }
            }
        }
        return values
    }

    /** Parameters that exist on this kernel, in registry order. */
    fun available(): List<KernelParameter> {
        val values = readAll()
        return all.filter { values.containsKey(it.path) }
    }

    fun read(path: String): String {
        return KeepShellPublic.doCmdSync("cat \"$path\" 2>/dev/null").trim()
    }

    /** Writes one value and reads it back; returns true only when the write took effect. */
    fun write(path: String, value: String): Boolean {
        KeepShellPublic.doCmdSync("echo \"$value\" > \"$path\" 2>/dev/null")
        return read(path) == value
    }
}
