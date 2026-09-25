package com.omarea.scene_mode

import android.content.Context
import com.omarea.store.SpfConfig
import com.omarea.utils.SceneLog
import com.omarea.scene_mode.options.ProfileOptions

/**
 * Boot-loop guard for the direct write backend.
 *
 * The app can persist kernel tweaks through a remount of the system partition.
 * If such a tweak ever prevents the device from coming up, the user has no easy
 * way back. This guard counts boots before the UI confirms success and, on the
 * second boot without a confirmation, silently reverts the risky boot state.
 */
object BootGuard {
    /** Called by BootWorker. Returns false when the boot loop guard tripped. */
    fun onBoot(context: Context): Boolean {
        val spf = context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
        val count = spf.getInt(SpfConfig.GLOBAL_SPF_BOOT_COUNT, 0) + 1
        spf.edit().putInt(SpfConfig.GLOBAL_SPF_BOOT_COUNT, count).apply()

        if (count < 2) {
            return true
        }

        SceneLog.w("BootGuard", "second boot without confirmation; reverting boot tweaks")
        spf.edit()
            .putBoolean(SpfConfig.GLOBAL_SPF_PROFILE_OPTIONS, false)
            .putString(SpfConfig.GLOBAL_SPF_POWERCFG, "")
            .putInt(SpfConfig.GLOBAL_SPF_BOOT_COUNT, 0)
            .apply()
        ProfileOptions.reset(context)
        return false
    }

    /** Called once the main screen is up: the device clearly booted fine. */
    fun markBootSuccessful(context: Context) {
        val spf = context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
        if (spf.getInt(SpfConfig.GLOBAL_SPF_BOOT_COUNT, 0) != 0) {
            spf.edit().putInt(SpfConfig.GLOBAL_SPF_BOOT_COUNT, 0).apply()
        }
    }
}
