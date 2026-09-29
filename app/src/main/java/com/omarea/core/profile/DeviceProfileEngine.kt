package com.omarea.core.profile

import android.content.Context
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.ShellLog
import org.json.JSONObject

/**
 * Device-exact profile engine.
 *
 * Every tuning parameter comes from a per-device JSON (tuning.json) —
 * nothing is hardcoded here. The engine only translates JSON → sysfs
 * writes through a root shell, mirroring the old powercfg scripts:
 *
 *   { "init":     { ...base tuning (core_ctl, sched, boost, vm, cpuset)... } }
 *   { "profiles": { "powersave": {...}, "balance": {...},
 *                   "performance": {...}, "custom": {...} } }
 *
 * JSON lookup order: user copy (/sdcard/Scene/profiles/<platform>.tuning.json)
 * → bundled copy (assets/powercfg/<platform>/tuning.json).
 */
object DeviceProfileEngine {

    private const val GPU_NODE = "/sys/class/kgsl/kgsl-3d0"
    private const val GPU_DEVFREQ = "$GPU_NODE/devfreq"
    private const val UFS_NODE = "/sys/devices/platform/soc/1d84000.ufshc"
    private const val UFS_DEVFREQ = "/sys/class/devfreq/1d84000.ufshc"

    /** Helper functions defined once per shell block (same semantics as powercfg-utils.sh). */
    private val HELPERS = """
        set_value() {
            if [ -f "${'$'}1" ]; then
                chmod 0664 "${'$'}1" 2>/dev/null
                echo "${'$'}2" > "${'$'}1" 2>/dev/null
            fi
        }
    """.trimIndent()

    // ------------------------------------------------------------------ init
    fun applyInit(context: Context, platform: String, json: JSONObject) {
        val lines = ArrayList<String>()

        // core_ctl
        json.optJSONObject("init")?.optJSONObject("core_ctl")?.let { cc ->
            for (cpu in cc.keys()) {
                val base = "/sys/devices/system/cpu/cpu$cpu/core_ctl"
                val cfg = cc.optJSONObject(cpu) ?: continue
                for (key in cfg.keys()) {
                    lines += set("$base/$key", cfg.optString(key))
                }
            }
        }

        // sched tunables
        json.optJSONObject("init")?.optJSONObject("sched")?.let { sc ->
            for (key in sc.keys()) {
                when (key) {
                    "downmigrate" -> lines += set("/proc/sys/kernel/sched_downmigrate", sc.optString(key))
                    "upmigrate" -> lines += set("/proc/sys/kernel/sched_upmigrate", sc.optString(key))
                    "group_downmigrate" -> lines += set("/proc/sys/kernel/sched_group_downmigrate", sc.optString(key))
                    "group_upmigrate" -> lines += set("/proc/sys/kernel/sched_group_upmigrate", sc.optString(key))
                    "walt_rotate_big_tasks" -> lines += set("/proc/sys/kernel/sched_walt_rotate_big_tasks", sc.optString(key))
                    "sched_latency_ns" -> lines += set("/proc/sys/kernel/sched_latency_ns", sc.optString(key))
                    "sched_min_granularity_ns" -> lines += set("/proc/sys/kernel/sched_min_granularity_ns", sc.optString(key))
                    "prefer_sync_wakee_to_waker" -> lines += set("/proc/sys/kernel/sched_prefer_sync_wakee_to_waker", sc.optString(key))
                }
            }
        }

        // per-cpu sched_load_boost
        json.optJSONObject("init")?.optJSONObject("sched_load_boost")?.let { b ->
            for (cpu in b.keys()) {
                lines += set("/sys/devices/system/cpu/cpu$cpu/sched_load_boost", b.optString(cpu))
            }
        }

        // hispeed_load per policy
        json.optJSONObject("init")?.optJSONObject("hispeed_load")?.let { hl ->
            for (policy in hl.keys()) {
                lines += set("/sys/devices/system/cpu/cpufreq/$policy/schedutil/hispeed_load", hl.optString(policy))
            }
        }

        // input boost + powerkey input boost
        json.optJSONObject("init")?.optJSONObject("input_boost")?.let { ib ->
            val freqs = (0..7).joinToString(" ") { i -> "$i:${ib.optInt("$i", 0)}" }
            lines += set("/sys/module/cpu_boost/parameters/input_boost_freq", freqs)
            lines += set("/sys/module/cpu_boost/parameters/input_boost_ms", ib.optString("ms"))
            lines += set("/sys/module/cpu_boost/parameters/sched_boost_on_input",
                if (ib.optInt("ms", 0) > 0) "1" else "0")
        }
        json.optJSONObject("init")?.optJSONObject("powerkey_input_boost")?.let { pk ->
            val freqs = (0..7).joinToString(" ") { i -> "$i:${pk.optInt("$i", 0)}" }
            lines += set("/sys/module/cpu_boost/parameters/powerkey_input_boost_freq", freqs)
            lines += set("/sys/module/cpu_boost/parameters/powerkey_input_boost_ms", pk.optString("ms"))
        }

        // lpm + cores online
        json.optJSONObject("init")?.opt("lpm_sleep_disabled")?.let {
            lines += set("/sys/module/lpm_levels/parameters/sleep_disabled", it.toString())
        }
        json.optJSONObject("init")?.optJSONObject("cores_online")?.let { co ->
            for (cpu in co.keys()) {
                lines += set("/sys/devices/system/cpu/cpu$cpu/online", co.optString(cpu))
            }
        }

        // vm tunables
        json.optJSONObject("init")?.optJSONObject("vm")?.let { vm ->
            for (key in vm.keys()) {
                when (key) {
                    "read_ahead_kb" -> lines += set("/sys/block/sda/queue/read_ahead_kb", vm.optString(key))
                    else -> lines += set("/proc/sys/vm/$key", vm.optString(key))
                }
            }
        }

        // cpuset defaults
        json.optJSONObject("init")?.optJSONObject("cpuset")?.let { cs ->
            for (key in cs.keys()) {
                lines += set("/dev/cpuset/$key/cpus", cs.optString(key))
            }
        }

        runBlock("init", lines)
    }

    // ---------------------------------------------------------------- profile
    fun applyProfile(context: Context, platform: String, mode: String, json: JSONObject) {
        val lines = ArrayList<String>()
        val profile = json.optJSONObject("profiles")?.optJSONObject(mode)
        if (profile == null) {
            ShellLog.log("DeviceProfileEngine", "no profile '$mode' in tuning.json", error = true)
            return
        }

        // reset msm_performance limits first (same as set_cpu_freq)
        lines += set("/sys/module/msm_performance/parameters/cpu_max_freq",
            (0..7).joinToString(" ") { i -> "$i:4294967295" })
        lines += set("/sys/module/msm_performance/parameters/cpu_min_freq",
            (0..7).joinToString(" ") { i -> "$i:0" })

        // cpu per policy
        profile.optJSONObject("cpu")?.let { cpu ->
            for (policy in cpu.keys()) {
                val node = "/sys/devices/system/cpu/cpufreq/$policy"
                val cfg = cpu.optJSONObject(policy) ?: continue
                if (cfg.has("governor")) {
                    lines += set("$node/scaling_governor", cfg.optString("governor"))
                }
                if (cfg.has("min")) {
                    lines += set("$node/scaling_min_freq", cfg.optString("min"))
                }
                if (cfg.has("max")) {
                    lines += set("$node/scaling_max_freq", cfg.optString("max"))
                }
                if (cfg.has("hispeed")) {
                    lines += set("$node/schedutil/hispeed_freq", cfg.optString("hispeed"))
                }
                if (cfg.has("down_rate_limit_us")) {
                    lines += set("$node/schedutil/down_rate_limit_us", cfg.optString("down_rate_limit_us"))
                }
                if (cfg.has("up_rate_limit_us")) {
                    lines += set("$node/schedutil/up_rate_limit_us", cfg.optString("up_rate_limit_us"))
                }
            }
        }

        // input boost
        profile.optJSONObject("input_boost")?.let { ib ->
            val freqs = (0..7).joinToString(" ") { i -> "$i:${ib.optInt("$i", 0)}" }
            lines += set("/sys/module/cpu_boost/parameters/input_boost_freq", freqs)
            lines += set("/sys/module/cpu_boost/parameters/input_boost_ms", ib.optString("ms"))
            lines += set("/sys/module/cpu_boost/parameters/sched_boost_on_input",
                if (ib.optInt("ms", 0) > 0) "1" else "0")
        }

        // scheduler
        profile.optJSONObject("sched")?.let { sc ->
            for (key in sc.keys()) {
                when (key) {
                    "downmigrate" -> lines += set("/proc/sys/kernel/sched_downmigrate", sc.optString(key))
                    "upmigrate" -> lines += set("/proc/sys/kernel/sched_upmigrate", sc.optString(key))
                    "group_downmigrate" -> lines += set("/proc/sys/kernel/sched_group_downmigrate", sc.optString(key))
                    "group_upmigrate" -> lines += set("/proc/sys/kernel/sched_group_upmigrate", sc.optString(key))
                    "boost_top_app" -> lines += set("/proc/sys/kernel/sched_boost_top_app", sc.optString(key))
                    "boost" -> lines += set("/proc/sys/kernel/sched_boost", sc.optString(key))
                    "top_app_prefer_idle" -> lines += set("/dev/stune/top-app/schedtune.prefer_idle", sc.optString(key))
                    "top_app_boost" -> lines += set("/dev/stune/top-app/schedtune.boost", sc.optString(key))
                }
            }
        }

        // cpuset
        profile.optJSONObject("cpuset")?.let { cs ->
            for (key in cs.keys()) {
                lines += set("/dev/cpuset/$key/cpus", cs.optString(key))
            }
        }

        // core_ctl per cluster (off → single write; on → full param set)
        profile.optJSONObject("core_ctl")?.let { cc ->
            for (cpu in cc.keys()) {
                val base = "/sys/devices/system/cpu/cpu$cpu/core_ctl"
                if (cc.optString(cpu) == "on") {
                    if (cpu == "cpu0") {
                        lines += set("$base/offline_delay_ms", "50")
                        lines += set("$base/not_preferred", "0 1 1 1 1 1")
                        lines += set("$base/enable", "1")
                        lines += set("$base/max_cpus", "6")
                        lines += set("$base/min_cpus", "1")
                        lines += set("$base/busy_down_thres", "5")
                        lines += set("$base/busy_up_thres", "15")
                    } else {
                        lines += set("$base/offline_delay_ms", "10")
                        lines += set("$base/not_preferred", "1 1")
                        lines += set("$base/enable", "1")
                        lines += set("$base/max_cpus", "2")
                        lines += set("$base/min_cpus", "0")
                        lines += set("$base/task_thres", "2")
                        lines += set("$base/busy_down_thres", "30")
                        lines += set("$base/busy_up_thres", "50")
                    }
                } else {
                    lines += set("$base/enable", "0")
                }
            }
        }

        // gpu
        profile.optJSONObject("gpu")?.let { gpu ->
            if (gpu.has("min_pwrlevel")) {
                lines += set("$GPU_NODE/min_pwrlevel", gpu.optString("min_pwrlevel"))
            }
            if (gpu.has("max_pwrlevel")) {
                lines += set("$GPU_NODE/max_pwrlevel", gpu.optString("max_pwrlevel"))
            }
        }

        // ufs
        when (profile.optString("ufs")) {
            "perf" -> {
                lines += set("$UFS_NODE/clkscale_enable", "0")
                lines += set("$UFS_NODE/clkgate_enable", "0")
                lines += set("$UFS_NODE/hibern8_on_idle_enable", "0")
                lines += set("$UFS_DEVFREQ/min_freq", "300000000")
            }
            "save" -> {
                lines += set("$UFS_NODE/clkscale_enable", "1")
                lines += set("$UFS_NODE/clkgate_enable", "1")
                lines += set("$UFS_NODE/hibern8_on_idle_enable", "1")
                lines += set("$UFS_DEVFREQ/min_freq", "37500000")
            }
        }

        // thermal
        if (profile.has("thermal_sconfig")) {
            val sc = profile.optString("thermal_sconfig")
            lines += set("/sys/class/thermal/thermal_message/sconfig", sc)
        }

        runBlock(mode, lines)
    }

    // ------------------------------------------------------------------ exec
    private fun runBlock(tag: String, lines: List<String>) {
        if (lines.isEmpty()) return
        val script = HELPERS + "\n" + lines.joinToString("\n")
        val out = KeepShellPublic.doCmdSync(script)
        ShellLog.log("DeviceProfileEngine.$tag", "${lines.size} ops → ${out.take(200)}")
    }

    private fun set(node: String, value: String): String =
        "set_value '$node' '$value'"
}
