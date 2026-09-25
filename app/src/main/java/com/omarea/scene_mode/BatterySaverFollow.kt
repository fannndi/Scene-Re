package com.omarea.scene_mode

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import com.omarea.Scene
import com.omarea.store.SpfConfig
import com.omarea.utils.SceneLog

/**
 * Follow the system battery saver with the powersave profile.
 *
 * Concept adapted from Encore Tweaks (Apache-2.0). Encore reacts to the
 * PowerManager binder callback, which is known not to fire on MIUI/HyperOS;
 * this implementation polls `PowerManager.isPowerSaveMode` plus the `low_power`
 * global from the watchdog and a broadcast receiver, so it works on Xiaomi
 * ROMs as well.
 */
object BatterySaverFollow {
    fun isEnabled(context: Context): Boolean =
        Scene.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_FOLLOW_SAVER, false)

    fun isBatterySaverOn(context: Context): Boolean {
        try {
            val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (power?.isPowerSaveMode == true) {
                return true
            }
        } catch (ex: Exception) {
            // fall through to the settings check
        }
        return try {
            Settings.Global.getInt(context.contentResolver, "low_power", 0) == 1
        } catch (ex: Exception) {
            false
        }
    }

    /** Switch to/from powersave when the system battery saver state changes. */
    fun check(context: Context) {
        try {
            if (!isEnabled(context)) {
                return
            }
            if (!Scene.getBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL, SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL_DEFAULT)) {
                return
            }
            val spf = Scene.globalConfig
            val backup = spf.getString(SpfConfig.GLOBAL_SPF_PROFILE_SAVER_BACKUP, "") ?: ""
            val saverOn = isBatterySaverOn(context)
            if (saverOn) {
                if (backup.isNotEmpty()) {
                    return
                }
                val current = ModeSwitcher.getCurrentPowerMode()
                if (current.isEmpty() || current == ModeSwitcher.POWERSAVE) {
                    return
                }
                spf.edit().putString(SpfConfig.GLOBAL_SPF_PROFILE_SAVER_BACKUP, current).apply()
                ModeSwitcher().executePowercfgMode(ModeSwitcher.POWERSAVE, context.packageName)
                SceneLog.i("BatterySaverFollow", "battery saver on: $current -> powersave")
            } else if (backup.isNotEmpty()) {
                spf.edit().putString(SpfConfig.GLOBAL_SPF_PROFILE_SAVER_BACKUP, "").apply()
                ModeSwitcher().executePowercfgMode(backup, context.packageName)
                SceneLog.i("BatterySaverFollow", "battery saver off: restored $backup")
            }
        } catch (ex: Exception) {
            SceneLog.e("BatterySaverFollow", "check failed", ex)
        }
    }

    /** Restore the previous mode when the option is turned off. */
    fun onOptionChanged(context: Context) {
        if (isEnabled(context)) {
            check(context)
            return
        }
        val spf = Scene.globalConfig
        val backup = spf.getString(SpfConfig.GLOBAL_SPF_PROFILE_SAVER_BACKUP, "") ?: ""
        if (backup.isNotEmpty()) {
            spf.edit().putString(SpfConfig.GLOBAL_SPF_PROFILE_SAVER_BACKUP, "").apply()
            ModeSwitcher().executePowercfgMode(backup, context.packageName)
        }
    }
}
