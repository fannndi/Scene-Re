package com.omarea.scene_mode

import android.content.Context
import com.omarea.utils.SceneLog
import com.omarea.scene_mode.options.ProfileOptions
import com.omarea.scene_mode.options.BatterySaverFollow
import com.omarea.scene_mode.power.BypassCharge

/**
 * Periodic re-apply of the values that vendor daemons like to overwrite while
 * the device is running (frequency limits, bypass-charging node state). A
 * re-apply is safer than locking the nodes read-only, because nothing can be
 * left behind in a broken state.
 *
 * Driven by the accessibility service timer, so it shares the same lifecycle as
 * the per-app switching logic.
 */
object ProfileWatchdog {
    private const val MIN_INTERVAL_MS = 55_000L

    private var lastRun = 0L

    fun tick(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastRun < MIN_INTERVAL_MS) {
            return
        }
        lastRun = now
        try {
            BatterySaverFollow.check(context)
            ProfileOptions.reapply(context)
            BypassCharge.reassert()
        } catch (ex: Exception) {
            SceneLog.e("ProfileWatchdog", "re-apply failed", ex)
        }
    }

    fun reset() {
        lastRun = 0L
    }
}
