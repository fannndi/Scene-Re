package com.omarea.runtime

import android.content.Context
import com.omarea.common.shell.ShellLog
import com.omarea.data.SpfConfig
import com.omarea.engine.ProfileController
import com.omarea.engine.RootShell
import com.omarea.util.CheckRootStatus

/**
 * Opt-in logging/telemetry reduction (AZenith-derived, docs/ATTRIBUTION.md).
 *
 * Stops the ROM's background loggers while the engine is ON so their CPU/IO
 * overhead and flash writes stop during gaming. `logd` itself is deliberately
 * NOT touched — logcat stays available for Diagnostics. Restored on toggle
 * off, engine OFF, TRUE OFF, cleanup and by the uninstall guard (a reboot
 * also restores everything).
 *
 * Responsibility: stop/start the logger services.
 * Non-goals: log levels, diagnostics.
 */
object LoggingReduction {

    /** Probe-verified service names on surya (missing ones are no-ops). */
    val SERVICES = listOf("statsd", "traced", "charge_logger")

    fun isEnabled(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
        .getBoolean(SpfConfig.GLOBAL_SPF_REDUCE_LOGGING, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
            .edit().putBoolean(SpfConfig.GLOBAL_SPF_REDUCE_LOGGING, enabled).apply()
        if (enabled) apply(context) else restore(context)
    }

    fun apply(context: Context) {
        val app = context.applicationContext
        if (!allowed(app)) return
        runCatching {
            RootShell.run(SERVICES.joinToString("\n") { "stop $it" })
            ShellLog.log("LoggingReduction", "stopped: ${SERVICES.joinToString()}")
        }
    }

    /** Own change: restoring is allowed on every exit path. */
    fun restore(context: Context) {
        runCatching {
            RootShell.run(SERVICES.joinToString("\n") { "start $it" })
            ShellLog.log("LoggingReduction", "restored: ${SERVICES.joinToString()}")
        }
    }

    private fun allowed(context: Context): Boolean =
        !TrueOff.isOff(context) &&
            !ProfileController.isEngineOff(context) &&
            CheckRootStatus.isAvailable()
}
