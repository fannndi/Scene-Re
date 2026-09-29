package com.omarea.core.profile

import android.content.Context
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.ShellLog
import org.json.JSONObject

/**
 * Device-exact profile engine.
 *
 * Every tuning parameter comes from a per-device JSON (tuning.json) —
 * nothing is hardcoded here. The engine translates JSON → sysfs writes
 * through a root shell, then verifies the writes (retry once on mismatch).
 *
 * Extras handled here:
 *  - hwui per-profile props (debug.hwui.renderer / ro.hwui.use_vulkan)
 *  - MIUI daemon lifecycle (mi_thermald / miuibooster stop-start)
 *  - scene_thermald coordination (profile_max handoff) + deployment
 */
object DeviceProfileEngine {

    private const val GPU_NODE = "/sys/class/kgsl/kgsl-3d0"
    private const val UFS_NODE = "/sys/devices/platform/soc/1d84000.ufshc"
    private const val UFS_DEVFREQ = "/sys/class/devfreq/1d84000.ufshc"
    private const val THERMALD_REMOTE = "/data/local/tmp/scene_thermald.sh"
    private const val PROFILE_MAX_FILE = "/data/local/tmp/scene_thermald.profile_max"

    /** Helper functions defined once per shell block (same semantics as powercfg-utils.sh). */
    private val HELPERS = """
        set_value() {
            if [ -f "${'$'}1" ]; then
                chmod 0664 "${'$'}1" 2>/dev/null
                echo "${'$'}2" > "${'$'}1" 2>/dev/null
            fi
        }
    """.trimIndent()

    private val applyLock = Any()

    // ------------------------------------------------------------------ init
    fun applyInit(context: Context, platform: String, json: JSONObject) {
        synchronized(applyLock) {
            val lines = ArrayList<String>()

            json.optJSONObject("init")?.optJSONObject("core_ctl")?.let { cc ->
                for (cpu in cc.keys()) {
                    val base = "/sys/devices/system/cpu/cpu$cpu/core_ctl"
                    val cfg = cc.optJSONObject(cpu) ?: continue
                    for (key in cfg.keys()) {
                        lines += set("$base/$key", cfg.optString(key))
                    }
                }
            }

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

            json.optJSONObject("init")?.optJSONObject("sched_load_boost")?.let { b ->
                for (cpu in b.keys()) {
                    lines += set("/sys/devices/system/cpu/cpu$cpu/sched_load_boost", b.optString(cpu))
                }
            }

            json.optJSONObject("init")?.optJSONObject("hispeed_load")?.let { hl ->
                for (policy in hl.keys()) {
                    lines += set("/sys/devices/system/cpu/cpufreq/$policy/schedutil/hispeed_load", hl.optString(policy))
                }
            }

            applyInputBoost(json.optJSONObject("init")?.optJSONObject("input_boost"), lines, "input_boost_freq")
            applyInputBoost(json.optJSONObject("init")?.optJSONObject("powerkey_input_boost"), lines, "powerkey_input_boost_freq")

            json.optJSONObject("init")?.opt("lpm_sleep_disabled")?.let {
                lines += set("/sys/module/lpm_levels/parameters/sleep_disabled", it.toString())
            }
            json.optJSONObject("init")?.optJSONObject("cores_online")?.let { co ->
                for (cpu in co.keys()) {
                    lines += set("/sys/devices/system/cpu/cpu$cpu/online", co.optString(cpu))
                }
            }

            json.optJSONObject("init")?.optJSONObject("vm")?.let { vm ->
                for (key in vm.keys()) {
                    when (key) {
                        "read_ahead_kb" -> lines += set("/sys/block/sda/queue/read_ahead_kb", vm.optString(key))
                        else -> lines += set("/proc/sys/vm/$key", vm.optString(key))
                    }
                }
            }

            json.optJSONObject("init")?.optJSONObject("cpuset")?.let { cs ->
                for (key in cs.keys()) {
                    lines += set("/dev/cpuset/$key/cpus", cs.optString(key))
                }
            }

            runBlock("init", lines)
        }
    }

    // ---------------------------------------------------------------- profile
    fun applyProfile(context: Context, platform: String, mode: String, json: JSONObject) {
        synchronized(applyLock) {
            runProfileBlock(platform, mode, json, writeProfileMax = true)
            // daemon lifecycle follows the profile engine state (ON)
            applyDaemonState(context, on = true)
        }
    }

    /** OFF transition: apply the bundled stock "release" profile and restore MIUI daemons. */
    fun applyRelease(context: Context, platform: String, json: JSONObject) {
        synchronized(applyLock) {
            runProfileBlock(platform, "release", json, writeProfileMax = false)
            // restore hwui defaults (remove per-profile overrides)
            KeepShellPublic.doCmdSync(
                "command -v resetprop >/dev/null 2>&1 && resetprop --delete debug.hwui.renderer; " +
                        "command -v resetprop >/dev/null 2>&1 && resetprop --delete ro.hwui.use_vulkan; true"
            )
            applyDaemonState(context, on = false)
        }
    }

    private fun runProfileBlock(platform: String, mode: String, json: JSONObject, writeProfileMax: Boolean) {
        val lines = ArrayList<String>()
        val profile = json.optJSONObject("profiles")?.optJSONObject(mode)
        if (profile == null) {
            ShellLog.log("DeviceProfileEngine", "no profile '$mode' in tuning.json", error = true)
            return
        }

        // device capability cache: available freqs + governors per policy
        val availFreqs = HashMap<String, List<Long>>()
        val availGovs = HashMap<String, List<String>>()
        for (policy in listOf("policy0", "policy6")) {
            val node = "/sys/devices/system/cpu/cpufreq/$policy"
            availFreqs[policy] = KeepShellPublic.doCmdSync("cat $node/scaling_available_frequencies")
                .trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
            availGovs[policy] = KeepShellPublic.doCmdSync("cat $node/scaling_available_governors")
                .trim().split(Regex("\\s+"))
        }

        // reset msm_performance limits first (same as set_cpu_freq)
        lines += set("/sys/module/msm_performance/parameters/cpu_max_freq",
            (0..7).joinToString(" ") { i -> "$i:4294967295" })
        lines += set("/sys/module/msm_performance/parameters/cpu_min_freq",
            (0..7).joinToString(" ") { i -> "$i:0" })

        // cpu per policy (validated + clamped)
        profile.optJSONObject("cpu")?.let { cpu ->
            for (policy in cpu.keys()) {
                val node = "/sys/devices/system/cpu/cpufreq/$policy"
                val cfg = cpu.optJSONObject(policy) ?: continue
                val freqs = availFreqs[policy] ?: emptyList()
                val govs = availGovs[policy] ?: emptyList()

                val gov = cfg.optString("governor", "")
                if (gov.isNotEmpty()) {
                    if (govs.isEmpty() || govs.contains(gov)) {
                        lines += set("$node/scaling_governor", gov)
                    } else {
                        ShellLog.log("DeviceProfileEngine", "$policy governor '$gov' not available, skipped", error = true)
                    }
                }
                if (cfg.has("min")) {
                    lines += set("$node/scaling_min_freq", clampFreq(cfg.getLong("min"), freqs).toString())
                }
                if (cfg.has("max")) {
                    lines += set("$node/scaling_max_freq", clampFreq(cfg.getLong("max"), freqs).toString())
                }
                if (cfg.has("hispeed")) {
                    lines += set("$node/schedutil/hispeed_freq", clampFreq(cfg.getLong("hispeed"), freqs).toString())
                }
                if (cfg.has("down_rate_limit_us")) {
                    lines += set("$node/schedutil/down_rate_limit_us", cfg.optString("down_rate_limit_us"))
                }
                if (cfg.has("up_rate_limit_us")) {
                    lines += set("$node/schedutil/up_rate_limit_us", cfg.optString("up_rate_limit_us"))
                }
            }
        }

        // cores online (per profile)
        profile.optJSONObject("cores_online")?.let { co ->
            for (cpu in co.keys()) {
                lines += set("/sys/devices/system/cpu/cpu$cpu/online", co.optString(cpu))
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

        // hwui per-profile props
        profile.optJSONObject("hwui")?.let { hwui ->
            if (hwui.has("renderer")) {
                lines += setProp("debug.hwui.renderer", hwui.optString("renderer"))
            }
            if (hwui.has("vulkan")) {
                lines += setProp("ro.hwui.use_vulkan", hwui.optString("vulkan"))
            }
        }

        runBlock(mode, lines)

        // verify + retry once
        val diffs = verifyCpu(profile)
        if (diffs.isNotEmpty()) {
            ShellLog.log("DeviceProfileEngine.verify", "mismatch ${diffs.size}, retrying", error = true)
            runBlock("$mode-retry", lines)
            val again = verifyCpu(profile)
            if (again.isNotEmpty()) {
                ShellLog.log("DeviceProfileEngine.verify", "still mismatched: $again", error = true)
            }
        }

        // hand off profile max values to scene_thermald (it only LOWERS when hot)
        if (writeProfileMax) {
            val p0max = profile.optJSONObject("cpu")?.optJSONObject("policy0")?.optLong("max")
            val p6max = profile.optJSONObject("cpu")?.optJSONObject("policy6")?.optLong("max")
            if (p0max != null && p6max != null) {
                KeepShellPublic.doCmdSync("echo '$p0max $p6max' > $PROFILE_MAX_FILE")
            }
        }
    }

    // ------------------------------------------------------- daemon lifecycle
    fun applyDaemonState(context: Context, on: Boolean) {
        if (on) {
            KeepShellPublic.doCmdSync("stop mi_thermald")
            KeepShellPublic.doCmdSync("stop miuibooster")
            ensureSceneThermaldRunning(context)
        } else {
            KeepShellPublic.doCmdSync("start mi_thermald")
            KeepShellPublic.doCmdSync("start miuibooster")
            KeepShellPublic.doCmdSync("pkill -f scene_thermald.sh 2>/dev/null; rm -f $PROFILE_MAX_FILE; true")
        }
    }

    private fun ensureSceneThermaldRunning(context: Context) {
        if (KeepShellPublic.doCmdSync("pgrep -f scene_thermald.sh").isNotBlank()) return
        deploySceneThermald(context)
        KeepShellPublic.doCmdSync(
            "nohup sh $THERMALD_REMOTE >/dev/null 2>&1 < /dev/null &"
        )
    }

    /** Deploys the bundled scene_thermald.sh to /data/local/tmp via su (SELinux-safe). */
    fun deploySceneThermald(context: Context) {
        try {
            val text = context.assets.open("scene_thermald.sh").bufferedReader().use { it.readText() }
            KeepShellPublic.doCmdSync(
                "cat > $THERMALD_REMOTE <<'SCENE_EOF'\n$text\nSCENE_EOF\nchmod 755 $THERMALD_REMOTE"
            )
        } catch (ex: Exception) {
            ShellLog.log("DeviceProfileEngine.deploy", ex.message ?: "error", error = true)
        }
    }

    // ---------------------------------------------------------------- verify
    private fun verifyCpu(profile: JSONObject): List<String> {
        val diffs = ArrayList<String>()
        val cpu = profile.optJSONObject("cpu") ?: return diffs
        for (policy in cpu.keys()) {
            val cfg = cpu.optJSONObject(policy) ?: continue
            val node = "/sys/devices/system/cpu/cpufreq/$policy"
            if (cfg.has("governor")) {
                val live = KeepShellPublic.doCmdSync("cat $node/scaling_governor").trim()
                if (live != cfg.optString("governor")) diffs += "$policy.governor=$live"
            }
            if (cfg.has("min")) {
                val live = KeepShellPublic.doCmdSync("cat $node/scaling_min_freq").trim()
                if (live != cfg.optString("min")) diffs += "$policy.min=$live"
            }
            if (cfg.has("max")) {
                val live = KeepShellPublic.doCmdSync("cat $node/scaling_max_freq").trim()
                if (live != cfg.optString("max")) diffs += "$policy.max=$live"
            }
        }
        return diffs
    }

    // ------------------------------------------------------------------ util
    private fun clampFreq(requested: Long, available: List<Long>): Long {
        if (available.isEmpty()) return requested
        if (available.contains(requested)) return requested
        var best = available.last()
        for (f in available) {
            if (f <= requested) best = f
            if (f >= requested) {
                best = if (f - requested < requested - best) f else best
                break
            }
        }
        return best
    }

    private fun set(node: String, value: String): String =
        "set_value '$node' '$value'"

    private fun setProp(prop: String, value: String): String =
        "if command -v resetprop >/dev/null 2>&1; then resetprop $prop '$value'; else setprop $prop '$value'; fi"

    private fun applyInputBoost(boost: JSONObject?, lines: MutableList<String>, node: String) {
        boost ?: return
        val freqs = (0..7).joinToString(" ") { i -> "$i:${boost.optInt("$i", 0)}" }
        lines += set("/sys/module/cpu_boost/parameters/$node", freqs)
        lines += set("/sys/module/cpu_boost/parameters/${node.removeSuffix("_freq")}_ms", boost.optString("ms"))
        if (node == "input_boost_freq") {
            lines += set("/sys/module/cpu_boost/parameters/sched_boost_on_input",
                if (boost.optInt("ms", 0) > 0) "1" else "0")
        }
    }

    private fun runBlock(tag: String, lines: List<String>) {
        if (lines.isEmpty()) return
        val script = HELPERS + "\n" + lines.joinToString("\n")
        val out = KeepShellPublic.doCmdSync(script)
        ShellLog.log("DeviceProfileEngine.$tag", "${lines.size} ops → ${out.take(200)}")
    }
}
