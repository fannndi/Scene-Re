package com.omarea.runtime

import android.content.Context
import com.omarea.common.shell.ShellLog
import com.omarea.data.SpfConfig
import com.omarea.engine.ProfileController
import com.omarea.engine.RootShell
import com.omarea.util.CheckRootStatus

/**
 * Game process priority boost (AZenith-derived, docs/ATTRIBUTION.md).
 *
 * When the foreground app owns a per-app mode and the user enabled the option,
 * its processes get `renice -20` + realtime I/O priority (best-effort
 * fallback). PID lookup retries briefly because a freshly launched game may
 * not have spawned yet at the app-switch moment.
 *
 * Responsibility: shell boost + logging.
 * Non-goals: deciding (pure [ProcessPriorityPolicy]), mode switching.
 */
object ProcessPriority {

    fun isEnabled(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
        .getBoolean(SpfConfig.GLOBAL_SPF_GAME_PRIORITY, false)

    /** Effective choice: per-app override wins, else the global toggle. */
    fun effectiveEnabled(context: Context, packageName: String): Boolean =
        GameExtras.priority(context, packageName) ?: isEnabled(context)

    fun shouldBoost(context: Context, packageName: String, appModeActive: Boolean): Boolean =
        ProcessPriorityPolicy.shouldBoost(
            enabled = effectiveEnabled(context, packageName),
            appModeActive = appModeActive,
            engineOff = ProfileController.isEngineOff(context),
            trueOff = TrueOff.isOff(context),
            rootAvailable = CheckRootStatus.isAvailable()
        )

    /** Fire-and-forget boost for [packageName]; safe from any thread. */
    fun boost(context: Context, packageName: String) {
        if (!shouldBoost(context, packageName, true)) return
        val app = context.applicationContext
        Thread {
            try {
                val script = buildString {
                    appendLine("pids=\"\"")
                    appendLine("for i in 1 2 3 4 5; do")
                    appendLine("  pids=\$(pgrep -f '$packageName' 2>/dev/null | head -n 8)")
                    appendLine("  [ -n \"\$pids\" ] && break")
                    appendLine("  sleep 0.2")
                    appendLine("done")
                    appendLine("[ -z \"\$pids\" ] && exit 0")
                    appendLine("renice -n -20 -p \$pids >/dev/null 2>&1")
                    appendLine("for p in \$pids; do")
                    appendLine("  ionice -c 1 -n 0 -p \$p >/dev/null 2>&1 || ionice -c 2 -n 0 -p \$p >/dev/null 2>&1")
                    appendLine("done")
                    appendLine("echo \"boosted \$pids\"")
                }
                val out = RootShell.run(script).trim()
                ShellLog.log("ProcessPriority", "$packageName ${out.take(120)}")
            } catch (ex: Exception) {
                ShellLog.log("ProcessPriority", ex.message ?: "error", error = true)
            }
        }.start()
    }
}
