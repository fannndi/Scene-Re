package com.omarea.runtime

import android.app.NotificationManager
import android.content.Context
import com.omarea.common.shell.ShellLog
import com.omarea.data.SpfConfig
import com.omarea.engine.ProfileController
import com.omarea.util.CheckRootStatus

/**
 * DND while an app-specific mode is active.
 *
 * "Game" = the foreground app has an explicit entry in the per-app mode store
 * (the preset game list included) and dynamic control is on. The interruption
 * filter before Scene touched it is restored on leave / engine OFF / TRUE OFF.
 *
 * Responsibility: own the DND toggle + remember/restore the filter.
 * Non-goals: deciding what to do (pure [DndPolicy]), app detection
 * ([AppSwitchHandler] calls in).
 */
object DndController {

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(SpfConfig.GLOBAL_SPF_DND_APP_MODE, false)

    fun isGranted(context: Context): Boolean = try {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .isNotificationPolicyAccessGranted
    } catch (_: Exception) {
        false
    }

    fun isActive(context: Context): Boolean =
        prefs(context).getBoolean(SpfConfig.GLOBAL_SPF_DND_ACTIVE, false)

    /** Status line for Diagnostics and the settings toggle. */
    fun describe(context: Context): String = buildString {
        append("enabled=").append(if (isEnabled(context)) "yes" else "no")
        append(" granted=").append(if (isGranted(context)) "yes" else "no")
        append(" active=").append(if (isActive(context)) "yes" else "no")
    }

    /** AppSwitchHandler entry point: app changed, [appModeActive] = explicit entry + dynamic control. */
    fun onForegroundApp(context: Context, appModeActive: Boolean) = evaluate(context, appModeActive)

    /** Re-derive the DND state; safe to call from any thread, never throws. */
    fun evaluate(context: Context, appModeActive: Boolean) {
        val app = context.applicationContext
        runCatching {
            val blocked = TrueOff.isOff(app) ||
                ProfileController.isEngineOff(app) ||
                !CheckRootStatus.isAvailable()
            when (DndPolicy.decide(
                enabled = isEnabled(app),
                granted = isGranted(app),
                blocked = blocked,
                appModeActive = appModeActive,
                dndActive = isActive(app)
            )) {
                DndPolicy.Action.ENTER -> enter(app)
                DndPolicy.Action.EXIT -> exit(app)
                DndPolicy.Action.NONE -> Unit
            }
        }
    }

    /** Restore the pre-Scene interruption filter (own change, allowed always). */
    fun exit(context: Context) {
        val app = context.applicationContext
        runCatching {
            val p = prefs(app)
            if (!p.getBoolean(SpfConfig.GLOBAL_SPF_DND_ACTIVE, false)) return
            val previous = p.getInt(
                SpfConfig.GLOBAL_SPF_DND_PREV_FILTER,
                NotificationManager.INTERRUPTION_FILTER_ALL
            )
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.setInterruptionFilter(previous)
            p.edit()
                .putBoolean(SpfConfig.GLOBAL_SPF_DND_ACTIVE, false)
                .remove(SpfConfig.GLOBAL_SPF_DND_PREV_FILTER)
                .apply()
            ShellLog.log("Dnd", "restored filter=$previous")
        }
    }

    private fun enter(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        prefs(context).edit()
            .putInt(SpfConfig.GLOBAL_SPF_DND_PREV_FILTER, nm.currentInterruptionFilter)
            .putBoolean(SpfConfig.GLOBAL_SPF_DND_ACTIVE, true)
            .apply()
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        ShellLog.log("Dnd", "app mode active -> DND on")
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
}
