package com.omarea.runtime

import android.content.Context
import com.omarea.common.shell.ShellLog
import com.omarea.data.GlobalStatus
import com.omarea.data.SpfConfig
import com.omarea.engine.RootShell
import com.omarea.util.CheckRootStatus

/**
 * Optional app restart after a display change (AZenith's `restart_target_app`).
 *
 * Renderer/resolution/refresh changes often only take effect after the target
 * process restarts. AZenith restarts the game automatically; Scene keeps this
 * **opt-in** (default OFF) because force-stopping the user's foreground app is
 * intrusive. Only the foreground app is restarted, and only when the user
 * changed a per-app display setting for it.
 *
 * Responsibility: the guarded one-shot restart.
 * Non-goals: applying the setting (DownscaleController).
 */
object DisplayRestart {

    fun isEnabled(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
        .getBoolean(SpfConfig.GLOBAL_SPF_DISPLAY_RESTART, false)

    /** Restarts [packageName] when it is the foreground app and allowed. */
    fun maybeRestart(context: Context, packageName: String) {
        val app = context.applicationContext
        if (!isEnabled(app)) return
        if (!CheckRootStatus.isAvailable()) return
        if (TrueOff.isOff(app)) return
        if (GlobalStatus.lastPackageName != packageName) return
        runCatching {
            RootShell.run("am force-stop '$packageName'")
            ShellLog.log("DisplayRestart", "restarted $packageName after display change")
        }
    }
}
