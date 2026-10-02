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

        init.optJSONObject("vm")?.let { vm -> ops += vmOps(vm) }

        init.optJSONObject("cpuset")?.let { cs -> ops += cpusetOps(cs) }

        // Encore-derived packs (Apache-2.0, see docs/ATTRIBUTION.md): network,
        // kernel/jitter sysctls, block-queue overhead and the sched_lib game
        // library reporting. All probe-gated by KernelCompat at apply time.
        init.optJSONObject("net")?.let { net -> ops += netOps(net, caps, warnings) }
        init.optJSONObject("kernel")?.let { kernel -> ops += kernelSysctlOps(kernel, warnings) }
        init.optJSONObject("io")?.let { io -> ops += ioOps(io, warnings) }
        init.optJSONObject("sched_lib")?.let { lib -> ops += schedLibOps(lib) }

        return ProfilePlan("init", applyMitigations(json, ops, warnings), warnings = warnings)
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
            inputBoostOps(ib, "input_boost_freq", ops)
        }
        profile.optJSONObject("powerkey_input_boost")?.let { pk ->
            inputBoostOps(pk, "powerkey_input_boost_freq", ops)
        }

        profile.opt("lpm_sleep_disabled")?.let {
            ops += ProfileOp(ShellNodes.LPM_SLEEP_DISABLED, it.toString())
        }

        // Workqueue power efficiency (AZenith-derived): N = lower latency,
        // Y = power-efficient unbound workqueues. Probe-gated at apply time.
        profile.opt("workqueue_power_efficient")?.let {
            ops += ProfileOp(ShellNodes.WORKQUEUE_POWER_EFFICIENT, it.toString())
        }

        profile.optJSONObject("vm")?.let { vm -> ops += vmOps(vm) }

        profile.optJSONObject("sched")?.let { sc -> ops += schedulerOps(sc) }

        // Per-profile overrides for keys the init block also carries: the big
        // cluster's hispeed_load and sched_load_boost are stock ROM values
        // (post_boot writes 85 / -6) that the release profile restores.
        profile.optJSONObject("hispeed_load")?.let { hl ->
            for (policy in hl.keys()) {
                ops += ProfileOp("${ShellNodes.cpufreq(policy)}/schedutil/hispeed_load", hl.optString(policy))
            }
        }
        profile.optJSONObject("sched_load_boost")?.let { lb ->
            for (cpu in lb.keys()) {
                ops += ProfileOp(ShellNodes.cpuNode(cpu, "sched_load_boost"), lb.optString(cpu))
            }
        }

        profile.optJSONObject("cpuset")?.let { cs -> ops += cpusetOps(cs) }

        profile.optJSONObject("core_ctl")?.let { cc ->
            for (cpu in cc.keys()) {
                ops += coreCtlOps(cpu, cc.opt(cpu))
            }
        }

        var gpuMaxPwr: Int? = null
        var gpuDefaultPwr: Int? = null
        var gpuThrottling: String? = null

        profile.optJSONObject("devfreq")?.let { df -> ops += devfreqOps(df, caps, warnings) }

        profile.optJSONObject("gpu")?.let { gpu ->
            // pwrlevels: 0 = highest clock. default_pwrlevel is the idle level
            // the msm-adreno-tz governor falls back to; throttling toggles GPU
            // thermal mitigation; thermal_pwrlevel is the thermal clamp slot
            // (normally owned by the thermal framework).
            for (key in listOf(
                "min_pwrlevel", "max_pwrlevel",
                "default_pwrlevel", "thermal_pwrlevel", "throttling",
                "bus_split", "force_clk_on"
            )) {
                if (gpu.has(key)) ops += ProfileOp("${ShellNodes.GPU}/$key", gpu.optString(key))
            }
            if (gpu.has("adrenoboost")) {
                ops += ProfileOp("${ShellNodes.GPU}/devfreq/adrenoboost", gpu.optString("adrenoboost"))
            }
            gpuMaxPwr = gpu.optString("max_pwrlevel").toIntOrNull()
            gpuDefaultPwr = gpu.optString("default_pwrlevel").toIntOrNull()
            gpuThrottling = gpu.optString("throttling").takeIf { it.isNotEmpty() }
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

        // Block I/O scheduler (AZenith-derived): validated against the live
        // scheduler list; unknown values are skipped and reported.
        profile.optString("io_scheduler").takeIf { it.isNotEmpty() }?.let { sched ->
            if (DeviceCaps.isGovernorAvailable(sched, caps.blockSchedulers)) {
                ops += ProfileOp(ShellNodes.BLOCK_SCHEDULER, sched)
            } else {
                warnings += "io scheduler '$sched' not available, skipped"
            }
        }

        // Per-profile block queue knobs (nr_requests/read_ahead_kb/iostats).
        profile.optJSONObject("io")?.let { io -> ops += ioOps(io, warnings) }

        if (profile.has("thermal_sconfig")) {
            ops += ProfileOp(ShellNodes.THERMAL_SCONFIG, profile.optString("thermal_sconfig"))
        }

        // LMK minfree: six ascending page counts (4 KB pages) as CSV.
        profile.optJSONObject("lmk")?.let { lmk ->
            if (lmk.has("minfree")) {
                ops += ProfileOp(ShellNodes.LMK_MINFREE, lmk.optString("minfree"))
            }
        }

        val profileMax = if (policy0Max != null && policy6Max != null) {
            policy0Max to policy6Max
        } else null

        val profileGpu = if (gpuMaxPwr != null || gpuDefaultPwr != null || gpuThrottling != null) {
            GpuThermal(gpuMaxPwr, gpuDefaultPwr, gpuThrottling)
        } else null

        return ProfilePlan(label, applyMitigations(json, ops, warnings), profileMax, profileGpu, warnings)
    }

    // ---------------------------------------------------------------- shared
    private fun vmOps(vm: JSONObject): List<ProfileOp> {
        val ops = ArrayList<ProfileOp>()
        for (key in vm.keys()) {
            val node = if (key == "read_ahead_kb") ShellNodes.READ_AHEAD_KB else "${ShellNodes.VM}/$key"
            ops += ProfileOp(node, vm.optString(key))
        }
        return ops
    }

    /** Known keys of the `kernel` init block -> `/proc/sys/kernel/<key>`. */
    private val KERNEL_SYSCTL_KEYS = setOf(
        "sched_nr_migrate", "sched_child_runs_first", "sched_autogroup_enabled",
        "perf_cpu_time_max_percent", "sched_schedstats", "sched_migration_cost_ns"
    )

    /** Known keys of the `io` block -> `<device>/queue/<key>`. */
    private val BLOCK_QUEUE_KEYS = setOf("iostats", "add_random", "nr_requests")

    /**
     * `net` block: `tcp_congestion` is a **preference list** resolved against
     * the running kernel's available algorithms; the scalar keys map straight
     * to `/proc/sys/net/ipv4/<key>`.
     */
    private fun netOps(net: JSONObject, caps: DeviceCaps, warnings: MutableList<String>): List<ProfileOp> {
        val ops = ArrayList<ProfileOp>()
        net.optJSONArray("tcp_congestion")?.let { preferred ->
            val list = (0 until preferred.length())
                .mapNotNull { preferred.optString(it).takeIf { name -> name.isNotEmpty() } }
            val chosen = DeviceCaps.firstAvailableCc(list, caps.tcpCc)
            if (chosen != null) {
                ops += ProfileOp("${ShellNodes.NET}/tcp_congestion_control", chosen)
            } else {
                warnings += "no preferred TCP congestion algorithm available (${list.joinToString()})"
            }
        }
        for (key in listOf("tcp_fastopen", "tcp_ecn", "tcp_sack", "tcp_low_latency")) {
            if (net.has(key)) ops += ProfileOp("${ShellNodes.NET}/$key", net.optString(key))
        }
        return ops
    }

    private fun kernelSysctlOps(kernel: JSONObject, warnings: MutableList<String>): List<ProfileOp> {
        val ops = ArrayList<ProfileOp>()
        for (key in kernel.keys()) {
            if (key in KERNEL_SYSCTL_KEYS) {
                ops += ProfileOp(ShellNodes.sched(key), kernel.optString(key))
            } else {
                warnings += "unknown kernel sysctl key '$key' ignored"
            }
        }
        return ops
    }

    private fun ioOps(io: JSONObject, warnings: MutableList<String>): List<ProfileOp> {
        val ops = ArrayList<ProfileOp>()
        for (device in io.keys()) {
            val cfg = io.optJSONObject(device) ?: continue
            for (key in cfg.keys()) {
                if (key in BLOCK_QUEUE_KEYS) {
                    ops += ProfileOp(ShellNodes.blockQueue(device, key), cfg.optString(key))
                } else {
                    warnings += "unknown io key '$device.$key' ignored"
                }
            }
        }
        return ops
    }

    private fun schedLibOps(lib: JSONObject): List<ProfileOp> {
        val ops = ArrayList<ProfileOp>()
        for (key in listOf("sched_lib_name", "sched_lib_mask_force")) {
            if (lib.has(key)) ops += ProfileOp(ShellNodes.sched(key), lib.optString(key))
        }
        return ops
    }

    /** Modes the `devfreq.latency` block accepts. */
    private val DEVFREQ_MODES = setOf("max", "mid", "min", "unlock")

    /**
     * `devfreq` profile block (Encore/AZenith-derived, docs/ATTRIBUTION.md):
     *
     *  - `latency`: `"max|mid|min|unlock"` — min/max OPP pinning across every
     *    kernel-detected CPU/bus latency domain (fallback/legacy).
     *  - `governor`: string (all domains) or `{ "<pattern>": "<governor>" }`
     *    (suffix patterns, `*` matches all, longest pattern wins) — the
     *    driver-native control. When the governor is offered by the domain it
     *    is written **instead of** the min/max ops for that domain; otherwise
     *    the latency mode applies (with a warning).
     */
    private fun devfreqOps(devfreq: JSONObject, caps: DeviceCaps, warnings: MutableList<String>): List<ProfileOp> {
        val ops = ArrayList<ProfileOp>()
        if (devfreq.length() == 0) return ops
        if (caps.devfreqLatency.isEmpty()) {
            warnings += "no devfreq latency domains detected"
            return ops
        }

        val mode = devfreq.optString("latency")
        if (mode.isNotEmpty() && mode !in DEVFREQ_MODES) {
            warnings += "unknown devfreq latency mode '$mode'"
        }
        val useLatency = mode in DEVFREQ_MODES

        val governorAll: String?
        val governorMap: Map<String, String>
        when (val spec = devfreq.opt("governor")) {
            is String -> {
                governorAll = spec.takeIf { it.isNotEmpty() }
                governorMap = emptyMap()
            }
            is JSONObject -> {
                governorAll = null
                governorMap = spec.keys().asSequence()
                    .associateWith { spec.optString(it) }
                    .filterValues { it.isNotEmpty() }
            }
            else -> {
                governorAll = null
                governorMap = emptyMap()
            }
        }
        if (devfreq.has("governor") && governorAll == null && governorMap.isEmpty()) {
            warnings += "empty devfreq.governor block"
        }
        val patterns = governorMap.keys.sortedByDescending { it.length }
        val unavailable = LinkedHashSet<String>()

        for ((domain, opps) in caps.devfreqLatency) {
            val governor = governorAll
                ?: patterns.firstOrNull { matchesDevfreqPattern(domain, it) }?.let { governorMap[it] }
            if (!governor.isNullOrEmpty()) {
                val available = caps.devfreqGovernors[domain].orEmpty()
                if (available.isEmpty() || governor in available) {
                    ops += ProfileOp(ShellNodes.devfreq(domain, "governor"), governor)
                } else {
                    unavailable += "$governor(${domain.substringAfterLast(',')})"
                }
            }
            if (!useLatency) continue

            val top = opps.last()
            val bottom = opps.first()
            val mid = DeviceCaps.midFreq(opps) ?: bottom
            when (mode) {
                "max" -> {
                    ops += ProfileOp(ShellNodes.devfreq(domain, "max_freq"), top.toString())
                    ops += ProfileOp(ShellNodes.devfreq(domain, "min_freq"), top.toString())
                }
                "mid" -> {
                    ops += ProfileOp(ShellNodes.devfreq(domain, "max_freq"), top.toString())
                    ops += ProfileOp(ShellNodes.devfreq(domain, "min_freq"), mid.toString())
                }
                "min" -> {
                    ops += ProfileOp(ShellNodes.devfreq(domain, "min_freq"), bottom.toString())
                    ops += ProfileOp(ShellNodes.devfreq(domain, "max_freq"), bottom.toString())
                }
                else -> { // unlock
                    ops += ProfileOp(ShellNodes.devfreq(domain, "max_freq"), top.toString())
                    ops += ProfileOp(ShellNodes.devfreq(domain, "min_freq"), bottom.toString())
                }
            }
        }
        for (entry in unavailable) warnings += "devfreq governor not available: $entry"
        return ops
    }

    /** Suffix match for `devfreq.governor` patterns (`*lat` / exact name). */
    private fun matchesDevfreqPattern(domain: String, pattern: String): Boolean {
        if (pattern == "*") return true
        return if (pattern.startsWith("*")) {
            domain.endsWith(pattern.substring(1))
        } else {
            domain == pattern || domain.endsWith(pattern)
        }
    }

    // ------------------------------------------------------------ mitigations
    /** Known mitigation ids (Encore-style device rules, docs/ATTRIBUTION.md). */
    val MITIGATION_IDS = setOf(
        "NO_PERFORMANCE_GOV", "NO_KGSL_FORCE_CLK", "NO_DDR_TWEAK", "NO_GPU_MIN_LOCK"
    )

    /** Active mitigation ids declared by the tuning document (known ids only). */
    fun mitigations(json: JSONObject): List<String> {
        val arr = json.optJSONArray("mitigations") ?: return emptyList()
        return (0 until arr.length())
            .mapNotNull { arr.optString(it).takeIf { id -> id.isNotEmpty() } }
            .filter { it in MITIGATION_IDS }
    }

    private fun disabledKeys(json: JSONObject): List<String> {
        val arr = json.optJSONArray("disabled_keys") ?: return emptyList()
        return (0 until arr.length())
            .mapNotNull { arr.optString(it).takeIf { key -> key.isNotEmpty() } }
    }

    /**
     * Drops ops suppressed by a declared mitigation or a `disabled_keys`
     * prefix and reports each reason once — a mitigation is never a silent
     * skip (rule 11's spirit for policy gating).
     */
    private fun applyMitigations(
        json: JSONObject,
        ops: List<ProfileOp>,
        warnings: MutableList<String>
    ): List<ProfileOp> {
        val ids = mitigations(json).toSet()
        val disabled = disabledKeys(json)
        if (ids.isEmpty() && disabled.isEmpty()) return ops
        val kept = ArrayList<ProfileOp>(ops.size)
        val reasons = LinkedHashSet<String>()
        for (op in ops) {
            val reason = mitigationReason(op, ids, disabled)
            if (reason == null) kept += op else reasons += reason
        }
        for (reason in reasons) warnings += "mitigation $reason: matching ops skipped"
        return kept
    }

    private fun mitigationReason(op: ProfileOp, ids: Set<String>, disabled: List<String>): String? {
        if ("NO_PERFORMANCE_GOV" in ids &&
            op.node.endsWith("/scaling_governor") && op.value == "performance"
        ) {
            return "NO_PERFORMANCE_GOV"
        }
        if ("NO_KGSL_FORCE_CLK" in ids && op.node.endsWith("/force_clk_on")) return "NO_KGSL_FORCE_CLK"
        if ("NO_DDR_TWEAK" in ids && op.node.startsWith("${ShellNodes.DEVFREQ}/")) return "NO_DDR_TWEAK"
        if ("NO_GPU_MIN_LOCK" in ids && op.node.endsWith("/min_pwrlevel")) return "NO_GPU_MIN_LOCK"
        val hit = disabled.firstOrNull { op.node.contains(it) }
        if (hit != null) return "disabled_keys[$hit]"
        return null
    }

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
                "sched_wakeup_granularity_ns" -> ShellNodes.sched("sched_wakeup_granularity_ns")
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
            // Explicit sched_boost_on_input wins; otherwise derive from ms.
            ops += ProfileOp(
                "${ShellNodes.CPU_BOOST}/sched_boost_on_input",
                boost.optString("sched_boost_on_input", if (boost.optInt("ms", 0) > 0) "1" else "0")
            )
        } else if (boost.has("sched_boost_on_powerkey_input")) {
            ops += ProfileOp(
                "${ShellNodes.CPU_BOOST}/sched_boost_on_powerkey_input",
                boost.optString("sched_boost_on_powerkey_input")
            )
        }
    }

    private fun coreCtlOps(cpu: String, raw: Any?): List<ProfileOp> {
        val base = ShellNodes.coreCtl(cpu)
        // Full object form: write exactly the declared keys (used by the
        // release profile to restore the ROM's post_boot core_ctl state).
        if (raw is JSONObject) {
            val ops = ArrayList<ProfileOp>()
            for (key in raw.keys()) {
                ops += ProfileOp("$base/$key", raw.optString(key))
            }
            return ops
        }
        val value = raw?.toString().orEmpty()
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
