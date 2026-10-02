package com.omarea.runtime

import android.content.Context
import com.omarea.common.shell.ShellLog
import com.omarea.engine.ProfileController
import com.omarea.engine.RootShell
import com.omarea.engine.ShellNodes
import com.omarea.engine.StockSnapshot

/**
 * Post-apply drift guard — the safety net for a ROM that writes after us.
 *
 * Right after the boot apply a small fingerprint of engine-owned nodes is
 * captured; a while later it is read again. When the ROM (or any other actor)
 * rewrote values in between, the boot state is re-applied once and the result
 * is logged plus persisted for Diagnostics. This covers the
 * `qcom-post-boot` race even when the [RomBootGate] wait is skipped or the
 * ROM starts writing late.
 *
 * Responsibility: fingerprint, compare, one re-apply, report.
 * Non-goals: the boot wait (see [RomBootGate]).
 */
object PostApplyDriftGuard {

    private const val PREFS = "scene_boot"
    private const val KEY_LAST = "drift_last"
    private const val KEY_APPLIED = "boot_applied_count"
    private const val KEY_WAIT = "post_boot_wait_ms"

    /** Nodes watched for late rewrites (subset of [StockSnapshot.nodes]). */
    val watchNodes: List<String> = buildList {
        for (policy in listOf("policy0", "policy6")) {
            val node = ShellNodes.cpufreq(policy)
            add("$node/scaling_governor")
            add("$node/scaling_min_freq")
            add("$node/scaling_max_freq")
            add("$node/schedutil/hispeed_freq")
        }
        add("${ShellNodes.CPU_BOOST}/input_boost_freq")
        add("${ShellNodes.CPU_BOOST}/input_boost_ms")
        add("${ShellNodes.coreCtl("cpu0")}/enable")
        add("${ShellNodes.coreCtl("cpu6")}/enable")
        add(ShellNodes.sched("sched_upmigrate"))
        add(ShellNodes.sched("sched_downmigrate"))
        add("${ShellNodes.CPUSET}/background/cpus")
        add("${ShellNodes.CPUSET}/foreground/cpus")
        add("${ShellNodes.CPUSET}/top-app/cpus")
        add(ShellNodes.LMK_MINFREE)
        add(ShellNodes.THERMAL_SCONFIG)
    }

    /** Pure diff: keys present on both sides whose value changed. */
    fun changedNodes(before: Map<String, String>, after: Map<String, String>): List<String> =
        before.keys.filter { key -> after.containsKey(key) && after[key] != before[key] }.sorted()

    private fun fingerprint(): Map<String, String>? = try {
        val out = RootShell.run(StockSnapshot.buildScript(watchNodes))
        StockSnapshot.parse(out).takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    /** Captures a fingerprint, checks it later and re-applies once on drift. */
    fun schedule(context: Context, delayMs: Long = 30_000L) {
        val app = context.applicationContext
        Thread {
            val before = fingerprint() ?: return@Thread
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (ProfileController.isEngineOff(app) || TrueOff.isOff(app)) return@Thread
            val after = fingerprint() ?: return@Thread
            val changed = changedNodes(before, after)
            if (changed.isEmpty()) {
                store(app, "ok (${watchNodes.size} nodes checked)")
                ShellLog.log("DriftGuard", "no drift after ${delayMs / 1000}s")
                return@Thread
            }
            ShellLog.log(
                "DriftGuard",
                "ROM rewrote ${changed.size} node(s) after apply: ${changed.take(6)} — re-applying",
                error = true
            )
            runCatching { ModeSwitcher().applyBootState() }
            store(app, "re-applied · ${changed.take(4).joinToString(", ")}")
        }.apply { isDaemon = true }.start()
    }

    fun lastResult(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST, null)

    fun storePostBootWait(context: Context, waitedMs: Long?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_WAIT, waitedMs ?: -1L).apply()
    }

    fun postBootWait(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_WAIT, -1L)

    /** Boot evidence for the autostart warning: which boot got an apply. */
    fun markBootApplied(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_APPLIED, StockSnapshot.bootCount(context)).apply()
    }

    fun bootAppliedCount(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_APPLIED, -1)

    private fun store(context: Context, result: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST, result).apply()
    }
}
