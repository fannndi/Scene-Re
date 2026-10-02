package com.omarea.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.omarea.common.shell.ShellLog
import com.omarea.data.SpfConfig
import com.omarea.engine.ProfileController
import com.omarea.util.CheckRootStatus

/**
 * Notification action receiver ("Enable engine").
 *
 * Posted from the status notification only while root is back and the engine
 * was auto-disabled by Monitor mode (`NoRootMode.isRestorePending`). This is
 * the shade-side twin of the Home restore card.
 *
 * Responsibility: one action → engine ON (never against TRUE OFF).
 * Non-goals: rendering (see [AlwaysNotification]).
 */
class ReceiverSceneAction : BroadcastReceiver() {

    companion object {
        const val ACTION_ENABLE_ENGINE = "com.omarea.runtime.action.ENABLE_ENGINE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ENABLE_ENGINE) return

        val pendingResult = goAsync()
        Thread {
            try {
                val app = context.applicationContext
                if (!CheckRootStatus.isAvailable()) return@Thread
                if (!NoRootMode.isRestorePending(app)) return@Thread
                // TRUE OFF owns the OFF state (rule 14) — never fight it.
                if (TrueOff.isOff(app)) return@Thread

                app.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
                    .edit().putBoolean(SpfConfig.GLOBAL_SPF_PROFILE_OFF, false).apply()
                NoRootMode.clearRestorePending(app)
                ProfileController.setEngineEnabled(app, true)
                ModeSwitcher().clearInitedState()
                ModeSwitcher().ensureReady()
                // Immediate feedback in the shade.
                runCatching { AlwaysNotification(app, true).notify() }
            } catch (ex: Exception) {
                ShellLog.log("ReceiverSceneAction", ex.message ?: "error", error = true)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}
