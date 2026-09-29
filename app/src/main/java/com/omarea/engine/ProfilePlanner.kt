package com.omarea.engine

import com.omarea.engine.ShellNodes
import org.json.JSONObject

/**
 * Translates a tuning JSON into an executable [ProfilePlan].
 *
 * Pure Kotlin: no Android imports, no shell, no side effects — unit-tested
 * without a device. Frequency requests are clamped to real OPPs and
 * governors are validated against [DeviceCaps]; anything skipped is reported
 * in [ProfilePlan.warnings] instead of failing the whole plan.
 *
 * Responsibility: JSON → ordered sysfs ops.
 * Non-goals: executing ops, daemon/property lifecycles (controllers own them).
 */
object ProfilePlanner {

    /** Number of CPU cores the boost masks cover (8 on the target device). */
    private const val CORE_COUNT = 8

    // ------------------------------------------------------------------ init
    fun planInit(json: JSONObject, caps: DeviceCaps): ProfilePlan {
        val ops = ArrayList<ProfileOp>()
        val warnings = ArrayList<String>()
        val init = json.optJSONObject("init")

        if (init == null) {
            return ProfilePlan("init", ops, warnings = listOf("tuning has no 'init' section"))
        }

        init.optJSONObject("core_ctl")?.let { cc ->
            for (cpu in cc.keys()) {
                val cfg = cc.optJSONObject(cpu) ?: continue
                for (key in cfg.keys()) {
                    ops += ProfileOp(ShellNodes.cpuNode(cpu, "core_ctl/$key"), cfg.optString(key))
                }
            }
        }

        init.optJSONObject("sched")?.let { sc -> ops += schedulerOps(sc) }

        init.optJSONObject("sched_load_boost")?.let { b ->
            for (cpu in b.keys()) {
                ops += ProfileOp(ShellNodes.cpuNode(cpu, "sched_load_boost"), b.optString(cpu))
            }
        }

        init.optJSONObject("hispeed_load")?.let { hl ->
            for (policy in hl.keys()) {
                ops += ProfileOp(
                    "${ShellNodes.cpufreq(policy)}/schedutil/hispeed_load",
                    hl.optString(policy)
                )
            }
        }

        inputBoostOps(init.optJSONObject("input_boost"), "input_boost_freq", ops)
        inputBoostOps(init.optJSONObject("powerkey_input_boost"), "powerkey_input_boost_freq", ops)

        init.opt("lpm_sleep_disabled")?.let {
            ops += ProfileOp(ShellNodes.LPM_SLEEP_DISABLED, it.toString())
        }

        coresOnlineOps(init.optJSONObject("cores_online"), ops)

        init.optJSONObject("vm")?.let { vm ->
            for (key in vm.keys()) {
                val node = if (key == "read_ahead_kb") ShellNodes.READ_AHEAD_KB else "${ShellNodes.VM}/$key"
                ops += ProfileOp(node, vm.optString(key))
            }
        }

        init.optJSONObject("cpuset")?.let { cs -> ops += cpusetOps(cs) }

        return ProfilePlan("init", ops, warnings = warnings)
    }

    // --------------------------------------------------------------- profile
    fun planProfile(json: JSONObject, mode: String, caps: DeviceCaps): ProfilePlan {
        val ops = ArrayList<ProfileOp>()
        val warnings = ArrayList<String>()
        val label = ProfileKey.canonical(mode)
        val profile = ProfileKey.profile(json.optJSONObject("profiles"), mode)

        if (profile == null) {
            return ProfilePlan(label, ops, warnings = listOf("profile '$mode' not found in tuning"))
        }

        // Reset Qualcomm msm_performance limits before touching cpufreq.
        ops += ProfileOp(
            "${ShellNodes.MSM_PERFORMANCE}/cpu_max_freq",
            (0 until CORE_COUNT).joinToString(" ") { "$it:4294967295" }
        )
        ops += ProfileOp(
            "${ShellNodes.MSM_PERFORMANCE}/cpu_min_freq",
            (0 until CORE_COUNT).joinToString(" ") { "$it:0" }
        )

        var policy0Max: Long? = null
        var policy6Max: Long? = null

        profile.optJSONObject("cpu")?.let { cpu ->
            for (policy in cpu.keys()) {
                val cfg = cpu.optJSONObject(policy) ?: continue
                val node = ShellNodes.cpufreq(policy)
                val freqs = caps.freqs[policy] ?: emptyList()
                val governors = caps.governors[policy] ?: emptyList()

                val governor = cfg.optString("governor", "")
                if (governor.isNotEmpty()) {
                    if (DeviceCaps.isGovernorAvailable(governor, governors)) {
                        ops += ProfileOp("$node/scaling_governor", governor)
                    } else {
                        warnings += "$policy governor '$governor' not available, skipped"
                    }
                }

                if (cfg.has("min")) {
                    ops += ProfileOp(
                        "$node/scaling_min_freq",
                        DeviceCaps.clampFreq(cfg.optLong("min"), freqs).toString()
                    )
                }

                var max: Long? = null
                if (cfg.has("max")) {
                    max = DeviceCaps.clampFreq(cfg.optLong("max"), freqs)
                    ops += ProfileOp("$node/scaling_max_freq", max.toString())
                }

                if (cfg.has("hispeed")) {
                    ops += ProfileOp(
                        "$node/schedutil/hispeed_freq",
                        DeviceCaps.clampFreq(cfg.optLong("hispeed"), freqs).toString()
                    )
                }
                if (cfg.has("down_rate_limit_us")) {
                    ops += ProfileOp("$node/schedutil/down_rate_limit_us", cfg.optString("down_rate_limit_us"))
                }
                if (cfg.has("up_rate_limit_us")) {
                    ops += ProfileOp("$node/schedutil/up_rate_limit_us", cfg.optString("up_rate_limit_us"))
                }

                when (policy) {
                    "policy0" -> max?.let { policy0Max = it }
                    "policy6" -> max?.let { policy6Max = it }
                }
            }
        }

        coresOnlineOps(profile.optJSONObject("cores_online"), ops)

        profile.optJSONObject("input_boost")?.let { ib ->
            val freqs = (0 until CORE_COUNT).joinToString(" ") { i -> "$i:${ib.optInt("$i", 0)}" }
            ops += ProfileOp("${ShellNodes.CPU_BOOST}/input_boost_freq", freqs)
            ops += ProfileOp("${ShellNodes.CPU_BOOST}/input_boost_ms", ib.optString("ms"))
            ops += ProfileOp(
                "${ShellNodes.CPU_BOOST}/sched_boost_on_input",
                if (ib.optInt("ms", 0) > 0) "1" else "0"
            )
        }

        profile.optJSONObject("sched")?.let { sc -> ops += schedulerOps(sc) }
        profile.optJSONObject("cpuset")?.let { cs -> ops += cpusetOps(cs) }

        profile.optJSONObject("core_ctl")?.let { cc ->
            for (cpu in cc.keys()) {
                ops += coreCtlOps(cpu, cc.optString(cpu))
            }
        }

        profile.optJSONObject("gpu")?.let { gpu ->
            if (gpu.has("min_pwrlevel")) ops += ProfileOp("${ShellNodes.GPU}/min_pwrlevel", gpu.optString("min_pwrlevel"))
            if (gpu.has("max_pwrlevel")) ops += ProfileOp("${ShellNodes.GPU}/max_pwrlevel", gpu.optString("max_pwrlevel"))
        }

        when (profile.optString("ufs")) {
            "perf" -> {
                ops += ProfileOp("${ShellNodes.UFS}/clkscale_enable", "0")
                ops += ProfileOp("${ShellNodes.UFS}/clkgate_enable", "0")
                ops += ProfileOp("${ShellNodes.UFS}/hibern8_on_idle_enable", "0")
                ops += ProfileOp("${ShellNodes.UFS_DEVFREQ}/min_freq", "300000000")
            }
            "save" -> {
                ops += ProfileOp("${ShellNodes.UFS}/clkscale_enable", "1")
                ops += ProfileOp("${ShellNodes.UFS}/clkgate_enable", "1")
                ops += ProfileOp("${ShellNodes.UFS}/hibern8_on_idle_enable", "1")
                ops += ProfileOp("${ShellNodes.UFS_DEVFREQ}/min_freq", "37500000")
            }
        }

        if (profile.has("thermal_sconfig")) {
            ops += ProfileOp(ShellNodes.THERMAL_SCONFIG, profile.optString("thermal_sconfig"))
        }

        val profileMax = if (policy0Max != null && policy6Max != null) {
            policy0Max!! to policy6Max!!
        } else null

        return ProfilePlan(label, ops, profileMax, warnings)
    }

    // ---------------------------------------------------------------- shared
    private fun coresOnlineOps(cores: JSONObject?, ops: MutableList<ProfileOp>) {
        cores ?: return
        for (cpu in cores.keys()) {
            ops += ProfileOp(ShellNodes.cpuNode(cpu, "online"), cores.optString(cpu))
        }
    }

    private fun cpusetOps(cpuset: JSONObject): List<ProfileOp> =
        cpuset.keys().asSequence()
            .map { ProfileOp("${ShellNodes.CPUSET}/$it/cpus", cpuset.optString(it)) }
            .toList()

    private fun schedulerOps(sched: JSONObject): List<ProfileOp> {
        val ops = ArrayList<ProfileOp>()
        for (key in sched.keys()) {
            val node = when (key) {
                "downmigrate" -> ShellNodes.sched("sched_downmigrate")
                "upmigrate" -> ShellNodes.sched("sched_upmigrate")
                "group_downmigrate" -> ShellNodes.sched("sched_group_downmigrate")
                "group_upmigrate" -> ShellNodes.sched("sched_group_upmigrate")
                "walt_rotate_big_tasks" -> ShellNodes.sched("sched_walt_rotate_big_tasks")
                "sched_latency_ns" -> ShellNodes.sched("sched_latency_ns")
                "sched_min_granularity_ns" -> ShellNodes.sched("sched_min_granularity_ns")
                "prefer_sync_wakee_to_waker" -> ShellNodes.sched("sched_prefer_sync_wakee_to_waker")
                "boost_top_app" -> ShellNodes.sched("sched_boost_top_app")
                "boost" -> ShellNodes.sched("sched_boost")
                "top_app_prefer_idle" -> "${ShellNodes.STUNE}/top-app/schedtune.prefer_idle"
                "top_app_boost" -> "${ShellNodes.STUNE}/top-app/schedtune.boost"
                else -> null
            }
            if (node != null) ops += ProfileOp(node, sched.optString(key))
        }
        return ops
    }

    private fun inputBoostOps(boost: JSONObject?, node: String, ops: MutableList<ProfileOp>) {
        boost ?: return
        val freqs = (0 until CORE_COUNT).joinToString(" ") { i -> "$i:${boost.optInt("$i", 0)}" }
        ops += ProfileOp("${ShellNodes.CPU_BOOST}/$node", freqs)
        ops += ProfileOp(
            "${ShellNodes.CPU_BOOST}/${node.removeSuffix("_freq")}_ms",
            boost.optString("ms")
        )
        if (node == "input_boost_freq") {
            ops += ProfileOp(
                "${ShellNodes.CPU_BOOST}/sched_boost_on_input",
                if (boost.optInt("ms", 0) > 0) "1" else "0"
            )
        }
    }

    private fun coreCtlOps(cpu: String, value: String): List<ProfileOp> {
        val base = ShellNodes.coreCtl(cpu)
        if (value != "on") {
            return listOf(ProfileOp("$base/enable", "0"))
        }
        return if (cpu == "cpu0") {
            listOf(
                ProfileOp("$base/offline_delay_ms", "50"),
                ProfileOp("$base/not_preferred", "0 1 1 1 1 1"),
                ProfileOp("$base/enable", "1"),
                ProfileOp("$base/max_cpus", "6"),
                ProfileOp("$base/min_cpus", "1"),
                ProfileOp("$base/busy_down_thres", "5"),
                ProfileOp("$base/busy_up_thres", "15")
            )
        } else {
            listOf(
                ProfileOp("$base/offline_delay_ms", "10"),
                ProfileOp("$base/not_preferred", "1 1"),
                ProfileOp("$base/enable", "1"),
                ProfileOp("$base/max_cpus", "2"),
                ProfileOp("$base/min_cpus", "0"),
                ProfileOp("$base/task_thres", "2"),
                ProfileOp("$base/busy_down_thres", "30"),
                ProfileOp("$base/busy_up_thres", "50")
            )
        }
    }
}
