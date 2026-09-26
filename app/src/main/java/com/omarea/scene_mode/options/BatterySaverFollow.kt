package com.omarea.scene_mode.options

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import com.omarea.Scene
import com.omarea.store.SpfConfig
import com.omarea.utils.SceneLog
import com.omarea.scene_mode.ModeSwitcher

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

    /**
     * Switch to/from powersave when the system battery saver state changes.
     *
     * Precedence: an active game beats the battery saver (Encore semantics),
     * everything else follows it. The previous mode is stored once and only
     * restored while the device is still on powersave, so a manual mode pick
     * made after the restore is never stomped by a stale backup.
     *
     * Scheduled on the profile worker: callers are broadcast receivers and
     * accessibility callbacks running on the main thread, and both the mode
     * read and the switch below are root-shell work.
     */
    fun check(context: Context) {
        ModeSwitcher.computeAsync({
            checkInternal(context)
            true
        }) { }
    }

    private fun checkInternal(context: Context) {
        try {
            if (!isEnabled(context)) {
                return
            }
            val spf = Scene.globalConfig
            val backup = spf.getString(SpfConfig.GLOBAL_SPF_PROFILE_SAVER_BACKUP, "") ?: ""
            val saverOn = isBatterySaverOn(context)

            if (saverOn) {
                // The game profile wins while a game is in the foreground.
                if (ProfileOptions.gameActive) {
                    return
                }
                val current = ModeSwitcher.getCurrentPowerMode()
                if (current.isEmpty() || current == ModeSwitcher.POWERSAVE) {
                    // Nothing configured yet, or already frugal (our override or
                    // the screen-off sleep mode); never record a backup from
                    // powersave itself.
                    return
                }
                if (backup.isEmpty()) {
                    spf.edit().putString(SpfConfig.GLOBAL_SPF_PROFILE_SAVER_BACKUP, current).apply()
                }
                ModeSwitcher().executePowercfgMode(ModeSwitcher.POWERSAVE, context.packageName)
                SceneLog.i("BatterySaverFollow", "battery saver on: $current -> powersave")
            } else if (backup.isNotEmpty()) {
                spf.edit().putString(SpfConfig.GLOBAL_SPF_PROFILE_SAVER_BACKUP, "").apply()
                val current = ModeSwitcher.getCurrentPowerMode()
                // Only undo the override while it is still in effect; if the
                // user picked another mode meanwhile, that choice stands.
                if (current == ModeSwitcher.POWERSAVE) {
                    ModeSwitcher().executePowercfgMode(backup, context.packageName)
                    SceneLog.i("BatterySaverFollow", "battery saver off: restored $backup")
                }
            }
        } catch (ex: Exception) {
            SceneLog.e("BatterySaverFollow", "check failed", ex)
        }
    }

    /** Restore the previous mode when the option is turned off. */
    fun onOptionChanged(context: Context) {
        ModeSwitcher.computeAsync({
            onOptionChangedInternal(context)
            true
        }) { }
    }

    private fun onOptionChangedInternal(context: Context) {
        if (isEnabled(context)) {
            checkInternal(context)
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
