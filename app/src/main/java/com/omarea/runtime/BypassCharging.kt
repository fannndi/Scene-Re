package com.omarea.runtime

import android.content.Context
import android.os.BatteryManager
import com.omarea.common.shell.ShellLog
import com.omarea.data.SpfConfig
import com.omarea.engine.ProfileController
import com.omarea.engine.RootShell
import com.omarea.util.CheckRootStatus

/**
 * Bypass charging — opt-in, threshold-based (AZenith-derived,
 * docs/ATTRIBUTION.md).
 *
 * While the user enables it and the device charges above the threshold, the
 * first available charge-control node is switched to its "stop charging"
 * value so the battery stops charging (the system stays powered where the
 * PMIC supports it). Unplugging, dropping below the threshold, engine OFF or
 * TRUE OFF restores the normal value.
 *
 * This is the single deliberate exception to the charging-read-only rule
 * (AGENTS.md rule 12) and the only charge writer besides
 * [com.omarea.runtime.ChargeStockRestorer]. The node choice is remembered so
 * restore always targets the same node; the guard/cleanup reset every
 * candidate.
 *
 * Responsibility: node selection + apply/restore + logging.
 * Non-goals: deciding (pure [BypassChargePolicy]).
 */
object BypassCharging {

    data class Node(val path: String, val onValue: String, val offValue: String)

    /** Probe-verified on surya, then generic fallbacks for other kernels. */
    val BYPASS_NODES = listOf(
        Node("/sys/class/power_supply/battery/battery_charging_enabled", "0", "1"),
        Node("/sys/class/power_supply/battery/input_suspend", "1", "0"),
        Node("/sys/class/qcom-battery/input_suspend", "1", "0")
    )

    /** `pause` mode = MIUI mishow semantics (input cut, runs on battery). */
    val PAUSE_NODES = listOf(
        Node("/sys/class/power_supply/battery/input_suspend", "1", "0")
    )

    /** Every candidate the reset paths must heal (deduplicated). */
    val CANDIDATES = (BYPASS_NODES + PAUSE_NODES).distinctBy { it.path }

    const val MODE_AUTO = "auto"
    const val MODE_BYPASS = "bypass"
    const val MODE_PAUSE = "pause"

    /** Node preference per mode (`auto` = true bypass first, then pause). */
    fun nodesFor(mode: String): List<Node> = when (mode) {
        MODE_BYPASS -> BYPASS_NODES.take(1)
        MODE_PAUSE -> PAUSE_NODES
        else -> BYPASS_NODES
    }

    /** Current node preference; `auto` keeps the MIUI mishow node as fallback. */
    fun mode(context: Context): String =
        prefs(context).getString(SpfConfig.GLOBAL_SPF_BYPASS_CHARGE_MODE, MODE_AUTO)
            ?.takeIf { it in setOf(MODE_AUTO, MODE_BYPASS, MODE_PAUSE) } ?: MODE_AUTO

    fun setMode(context: Context, mode: String) {
        prefs(context).edit().putString(SpfConfig.GLOBAL_SPF_BYPASS_CHARGE_MODE, mode).apply()
        evaluate(context)
    }

    private const val KEY_ACTIVE = "bypass_charge_active"
    private const val KEY_NODE = "bypass_charge_node"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(SpfConfig.GLOBAL_SPF_BYPASS_CHARGE, false)

    fun threshold(context: Context): Int =
        prefs(context).getInt(SpfConfig.GLOBAL_SPF_BYPASS_CHARGE_THRESHOLD, 80).coerceIn(40, 100)

    fun isActive(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ACTIVE, false)

    fun activeNode(context: Context): String? =
        prefs(context).getString(KEY_NODE, null)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(SpfConfig.GLOBAL_SPF_BYPASS_CHARGE, enabled).apply()
        evaluate(context)
    }

    fun setThreshold(context: Context, threshold: Int) {
        prefs(context).edit()
            .putInt(SpfConfig.GLOBAL_SPF_BYPASS_CHARGE_THRESHOLD, threshold.coerceIn(40, 100))
            .apply()
        evaluate(context)
    }

    /** Status line for Diagnostics / the settings summary. */
    fun describe(context: Context): String = buildString {
        append("enabled=").append(if (isEnabled(context)) "yes" else "no")
        append(" active=").append(if (isActive(context)) "yes" else "no")
        append(" threshold=").append(threshold(context)).append("%")
        append(" mode=").append(mode(context))
        activeNode(context)?.let { append(" node=").append(it.substringAfterLast('/')) }
    }

    /** Receiver / boot / app-open entry point; never throws. */
    fun evaluate(context: Context) {
        val app = context.applicationContext
        runCatching {
            val bm = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging
            val action = BypassChargePolicy.decide(
                enabled = isEnabled(app),
                engineOff = ProfileController.isEngineOff(app),
                trueOff = TrueOff.isOff(app),
                rootAvailable = CheckRootStatus.isAvailable(),
                charging = charging,
                level = level,
                threshold = threshold(app),
                active = isActive(app)
            )
            when (action) {
                BypassChargePolicy.Action.ENABLE -> {
                    val node = firstExistingNode(nodesFor(mode(app))) ?: return@runCatching
                    RootShell.run("echo ${node.onValue} > ${node.path}")
                    prefs(app).edit()
                        .putBoolean(KEY_ACTIVE, true)
                        .putString(KEY_NODE, node.path)
                        .apply()
                    ShellLog.log("BypassCharging", "charging paused at $level% (${node.path})")
                }
                BypassChargePolicy.Action.DISABLE -> disable(app)
                BypassChargePolicy.Action.NONE -> Unit
            }
        }
    }

    /** Boot: the node resets with the kernel, so a stale flag is cleared. */
    fun evaluateAtBoot(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, false)
            .remove(KEY_NODE)
            .apply()
        evaluate(context)
    }

    /** Restore the remembered node (own change). */
    fun disable(context: Context) {
        val app = context.applicationContext
        runCatching {
            val node = activeNode(app)?.let { path -> CANDIDATES.firstOrNull { it.path == path } }
            if (node != null) {
                RootShell.run("echo ${node.offValue} > ${node.path}")
                ShellLog.log("BypassCharging", "charging restored (${node.path})")
            }
            prefs(app).edit()
                .putBoolean(KEY_ACTIVE, false)
                .remove(KEY_NODE)
                .apply()
        }
    }

    /**
     * Unconditional safe reset: writes the off value to every candidate that
     * exists. Used by engine OFF / TRUE OFF / cleanup / the uninstall guard.
     */
    fun forceReset(context: Context) {
        runCatching {
            val script = CANDIDATES.joinToString("\n") {
                "if [ -e '${it.path}' ]; then echo '${it.offValue}' > '${it.path}' 2>/dev/null; fi"
            }
            RootShell.run(script)
            prefs(context).edit()
                .putBoolean(KEY_ACTIVE, false)
                .remove(KEY_NODE)
                .apply()
        }
    }

    private fun firstExistingNode(candidates: List<Node>): Node? {
        val output = RootShell.run(
            candidates.joinToString("; ") { "[ -e '${it.path}' ] && echo '${it.path}'" }
        )
        val found = output.lines().map { it.trim() }.toSet()
        return candidates.firstOrNull { it.path in found }
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
}
