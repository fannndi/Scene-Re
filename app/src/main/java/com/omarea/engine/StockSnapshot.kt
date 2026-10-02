package com.omarea.engine

import android.content.Context
import android.os.Environment
import android.provider.Settings
import com.omarea.common.shell.ShellLog
import com.omarea.runtime.TrueOff
import com.omarea.util.CheckRootStatus
import org.json.JSONObject
import java.io.File

/**
 * Pre-engine stock snapshot — the real "stock" for engine OFF.
 *
 * On the first engine write of every boot the current value of every node the
 * app may touch is captured (after the ROM's `qcom-post-boot` finished, see
 * `RomBootGate`). Engine OFF then restores exactly that snapshot instead of an
 * approximation of "stock": ROM updates, different GPU bins and kernel
 * defaults all heal themselves, and a fresh boot simply recaptures.
 *
 * Responsibility: capture + persist + turn the snapshot into a restore plan.
 * Non-goals: deciding when to restore ([ProfileController.release]).
 */
object StockSnapshot {

    private const val PREFS = "scene_stock"
    private const val KEY_BOOT = "boot_count"
    private const val KEY_DATA = "nodes"
    private const val KEY_AT = "captured_at"

    private const val CORE_COUNT = 8
    private const val MARK_START = "@@node:"
    private const val MARK_OK = "@@ok"
    private const val MARK_END = "@@end"

    /** Every node the engine may write (init + all profiles + guard). */
    val nodes: List<String> = buildList {
        // CPU per policy
        for (policy in DeviceCaps.POLICIES) {
            val node = ShellNodes.cpufreq(policy)
            add("$node/scaling_governor")
            add("$node/scaling_min_freq")
            add("$node/scaling_max_freq")
            add("$node/schedutil/hispeed_freq")
            add("$node/schedutil/hispeed_load")
            add("$node/schedutil/up_rate_limit_us")
            add("$node/schedutil/down_rate_limit_us")
        }
        // CPU boost (input + power key)
        add("${ShellNodes.CPU_BOOST}/input_boost_freq")
        add("${ShellNodes.CPU_BOOST}/input_boost_ms")
        add("${ShellNodes.CPU_BOOST}/sched_boost_on_input")
        add("${ShellNodes.CPU_BOOST}/powerkey_input_boost_freq")
        add("${ShellNodes.CPU_BOOST}/powerkey_input_boost_ms")
        add("${ShellNodes.CPU_BOOST}/sched_boost_on_powerkey_input")
        // core_ctl
        for (cpu in listOf("cpu0", "cpu6")) {
            val base = ShellNodes.coreCtl(cpu)
            for (leaf in listOf(
                "enable", "not_preferred", "min_cpus", "max_cpus",
                "busy_up_thres", "busy_down_thres", "offline_delay_ms",
                "task_thres", "is_big_cluster"
            )) {
                add("$base/$leaf")
            }
        }
        // scheduler
        for (name in listOf(
            "sched_downmigrate", "sched_upmigrate",
            "sched_group_downmigrate", "sched_group_upmigrate",
            "sched_walt_rotate_big_tasks", "sched_boost", "sched_boost_top_app",
            "sched_latency_ns", "sched_min_granularity_ns",
            "sched_wakeup_granularity_ns", "sched_little_cluster_coloc_fmin_khz",
            "sched_prefer_sync_wakee_to_waker"
        )) {
            add(ShellNodes.sched(name))
        }
        add(ShellNodes.cpuNode("cpu6", "sched_load_boost"))
        add(ShellNodes.cpuNode("cpu7", "sched_load_boost"))
        // stune
        add("${ShellNodes.STUNE}/top-app/schedtune.boost")
        add("${ShellNodes.STUNE}/top-app/schedtune.prefer_idle")
        // cpusets
        for (set in listOf("background", "system-background", "foreground", "foreground/boost", "top-app", "restricted")) {
            add("${ShellNodes.CPUSET}/$set/cpus")
        }
        // vm / block
        for (key in listOf(
            "dirty_background_ratio", "dirty_ratio", "dirty_expire_centisecs",
            "dirty_writeback_centisecs", "overcommit_ratio", "vfs_cache_pressure",
            "page_cluster", "swap_ratio", "stat_interval"
        )) {
            add("${ShellNodes.VM}/$key")
        }
        add(ShellNodes.READ_AHEAD_KB)
        // Network / kernel jitter sysctls + sched_lib (Encore-derived packs).
        for (name in listOf(
            "tcp_congestion_control", "tcp_fastopen", "tcp_ecn",
            "tcp_sack", "tcp_low_latency"
        )) {
            add("${ShellNodes.NET}/$name")
        }
        for (name in listOf(
            "sched_nr_migrate", "sched_child_runs_first", "sched_autogroup_enabled",
            "perf_cpu_time_max_percent", "sched_schedstats", "sched_migration_cost_ns",
            "sched_lib_name", "sched_lib_mask_force"
        )) {
            add(ShellNodes.sched(name))
        }
        add(ShellNodes.WORKQUEUE_POWER_EFFICIENT)
        for (key in listOf("iostats", "add_random", "nr_requests")) {
            add(ShellNodes.blockQueue("sda", key))
        }
        add(ShellNodes.BLOCK_SCHEDULER)
        // gpu
        for (leaf in listOf(
            "min_pwrlevel", "max_pwrlevel", "default_pwrlevel", "thermal_pwrlevel",
            "throttling", "bus_split", "force_clk_on"
        )) {
            add("${ShellNodes.GPU}/$leaf")
        }
        add("${ShellNodes.GPU}/devfreq/adrenoboost")
        // ufs
        for (leaf in listOf("clkscale_enable", "clkgate_enable", "hibern8_on_idle_enable")) {
            add("${ShellNodes.UFS}/$leaf")
        }
        add("${ShellNodes.UFS_DEVFREQ}/min_freq")
        // lm / thermal / lpm
        add(ShellNodes.LMK_MINFREE)
        add(ShellNodes.THERMAL_SCONFIG)
        add("${ShellNodes.CPU}/cpu4/online")
        add("${ShellNodes.CPU}/cpu5/online")
        add("${ShellNodes.CPU}/cpu6/online")
        add("${ShellNodes.CPU}/cpu7/online")
        add(ShellNodes.LPM_SLEEP_DISABLED)
    }

    data class Data(val bootCount: Int, val nodes: Map<String, String>, val capturedAt: Long)

    /** Read script: marker line, optional `@@ok` (node exists), value, end marker per node. */
    fun buildScript(paths: List<String> = nodes): String =
        paths.joinToString("\n") { path ->
            "echo '$MARK_START$path'\n" +
                "if [ -e '$path' ]; then echo '$MARK_OK'; cat '$path' 2>/dev/null; fi\n" +
                "echo '$MARK_END'"
        } + "\n" + devfreqBusScript()

    /**
     * Devfreq latency domains are kernel-detected, not a fixed list: the
     * capture script discovers the same `soc:qcom,cpu*lat|*latfloor` set as
     * [DeviceCaps] and emits their min/max nodes through the same markers, so
     * engine OFF restores exact pre-engine values on any kernel.
     */
    private fun devfreqBusScript(): String = buildString {
        appendLine("for d in ${ShellNodes.DEVFREQ}/*; do")
        appendLine("  n=${'$'}{d##*/}")
        appendLine("  case \"${'$'}n\" in")
        appendLine("    ${DeviceCaps.DEVFREQ_PREFIX}*lat|${DeviceCaps.DEVFREQ_PREFIX}*latfloor)")
        appendLine("      for leaf in min_freq max_freq governor; do")
        appendLine("        echo \"$MARK_START${'$'}d/${'$'}leaf\"")
        appendLine("        if [ -e \"${'$'}d/${'$'}leaf\" ]; then echo \"$MARK_OK\"; cat \"${'$'}d/${'$'}leaf\" 2>/dev/null; fi")
        appendLine("        echo \"$MARK_END\"")
        appendLine("      done")
        appendLine("      ;;")
        appendLine("  esac")
        appendLine("done")
    }

    /**
     * Pure parser for [buildScript] output. A node is captured when its `@@ok`
     * marker is present (value may be empty — e.g. `sched_lib_name` stock) or
     * when it carries a non-empty value (snapshots from older builds).
     */
    fun parse(output: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var current: String? = null
        var exists = false
        val value = StringBuilder()
        for (line in output.lines()) {
            when {
                line.startsWith(MARK_START) -> {
                    current = line.removePrefix(MARK_START).trim()
                    exists = false
                    value.setLength(0)
                }
                line.trim() == MARK_OK -> exists = true
                line.trim() == MARK_END -> {
                    val key = current
                    if (key != null && key.isNotEmpty()) {
                        val text = value.toString().trim()
                        if (exists || text.isNotEmpty()) result[key] = text
                    }
                    current = null
                    exists = false
                }
                current != null -> {
                    if (value.isNotEmpty()) value.append('\n')
                    value.append(line)
                }
            }
        }
        return result
    }

    /** Current boot counter (Settings.Global) or -1 when unavailable. */
    fun bootCount(context: Context): Int = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    } catch (_: Exception) {
        -1
    }

    /**
     * Captures the snapshot once per boot, before the first engine write.
     * No-op (and cheap) on later calls; never runs while TRUE OFF is active.
     */
    fun ensureCaptured(context: Context): Data? {
        if (TrueOff.isOff(context)) return null
        // Monitor mode: reading is fine, but the snapshot is only meaningful
        // right before a real apply — skip without root to save a shell trip.
        if (!CheckRootStatus.isAvailable()) return null
        val boot = bootCount(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = current(context)
        if (stored != null && (boot <= 0 || stored.bootCount == boot)) return stored

        val out = runCatching { RootShell.run(buildScript()) }.getOrNull() ?: return null
        val parsed = parse(out)
        if (parsed.isEmpty()) return null
        val data = Data(boot, parsed, System.currentTimeMillis())
        prefs.edit()
            .putInt(KEY_BOOT, boot)
            .putLong(KEY_AT, data.capturedAt)
            .putString(KEY_DATA, JSONObject(parsed as Map<*, *>).toString())
            .apply()
        writeDebugDump(context, data)
        ShellLog.log("StockSnapshot", "captured ${parsed.size} nodes (boot $boot)")
        return data
    }

    fun current(context: Context): Data? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val text = prefs.getString(KEY_DATA, null) ?: return null
        return try {
            val obj = JSONObject(text)
            val map = LinkedHashMap<String, String>()
            obj.keys().forEach { key -> map[key] = obj.optString(key) }
            Data(prefs.getInt(KEY_BOOT, -1), map, prefs.getLong(KEY_AT, 0L))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Restore plan: the msm_performance lock release a normal apply performs,
     * followed by every captured node's raw value.
     */
    fun restorePlan(context: Context): ProfilePlan? {
        val data = current(context) ?: return null
        if (data.nodes.isEmpty()) return null
        val ops = ArrayList<ProfileOp>()
        ops += ProfileOp(
            "${ShellNodes.MSM_PERFORMANCE}/cpu_max_freq",
            (0 until CORE_COUNT).joinToString(" ") { "$it:4294967295" }
        )
        ops += ProfileOp(
            "${ShellNodes.MSM_PERFORMANCE}/cpu_min_freq",
            (0 until CORE_COUNT).joinToString(" ") { "$it:0" }
        )
        data.nodes.forEach { (node, value) ->
            // Blank values are captured empties (nodes that accept an empty
            // string, e.g. sched_lib_name) — restoring them clears the node.
            ops += ProfileOp(node, value)
        }
        return ProfilePlan("stock-snapshot", ops)
    }

    /** Short status line for diagnostics. */
    fun status(context: Context): String {
        val data = current(context) ?: return "not captured"
        val age = System.currentTimeMillis() - data.capturedAt
        return "boot ${data.bootCount} · ${data.nodes.size} nodes · ${age / 1000}s ago"
    }

    private fun writeDebugDump(context: Context, data: Data) {
        runCatching {
            val dir = File(Environment.getExternalStorageDirectory(), "Scene/debug")
            dir.mkdirs()
            val obj = JSONObject()
            obj.put("boot_count", data.bootCount)
            obj.put("captured_at", data.capturedAt)
            obj.put("nodes", JSONObject(data.nodes as Map<*, *>))
            File(dir, "stock-snapshot.json").writeText(obj.toString(2))
        }
    }
}
