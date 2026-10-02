package com.omarea.runtime

import android.content.Context
import com.omarea.data.SpfConfig
import com.omarea.engine.ProfileController
import com.omarea.util.CheckRootStatus

/**
 * Foreground handling for the a11y-free fallback watcher.
 *
 * [AppSwitchHandler] owns the accessibility path; this is the same per-app
 * pipeline (mode + display overrides + game extras + DND) driven by the root
 * watcher's broadcast instead of an accessibility event. It deliberately
 * re-reads the per-app store instead of sharing AppSwitchHandler's fields.
 *
 * Responsibility: one foreground package -> the same actions as an a11y
 * app-switch.
 * Non-goals: watching (RootForegroundWatch), deciding (ModeSwitcher).
 */
object ForegroundFallback {

    fun handle(context: Context, packageName: String) {
        val app = context.applicationContext
        if (!RootForegroundWatch.isEnabled(app)) return
        if (TrueOff.isOff(app) || ProfileController.isEngineOff(app)) return
        if (!CheckRootStatus.isAvailable()) return

        val global = app.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
        if (!global.getBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL, SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL_DEFAULT)) {
            return
        }

        val power = app.getSharedPreferences(SpfConfig.POWER_CONFIG_SPF, Context.MODE_PRIVATE)
        val firstMode = global.getString(SpfConfig.GLOBAL_SPF_POWERCFG_FIRST_MODE, ModeSwitcher.BALANCE)
            ?: ModeSwitcher.BALANCE
        val mode = power.getString(packageName, firstMode) ?: firstMode
        val appModeActive = power.contains(packageName) && mode != ModeSwitcher.IGONED

        if (mode != ModeSwitcher.IGONED) {
            ModeSwitcher().executePowercfgMode(mode, packageName)
        }
        RefreshRateController.applyForApp(app, packageName)
        DownscaleController.applyForApp(app, packageName)
        DndController.onForegroundApp(app, appModeActive)
        if (appModeActive) {
            ProcessPriority.boost(app, packageName)
            GamePreload.preload(app, packageName)
        }
    }
}
