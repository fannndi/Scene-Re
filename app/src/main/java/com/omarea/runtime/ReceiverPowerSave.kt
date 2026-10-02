package com.omarea.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

/**
 * System battery-saver toggle -> [BatterySaverMode].
 *
 * Registered both in the manifest (works when the process is alive and the
 * broadcast is exempted from implicit-broadcast limits) and dynamically by
 * the accessibility service (guaranteed delivery on Android 12+).
 *
 * Responsibility: one broadcast, one call.
 */
class ReceiverPowerSave : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) {
            BatterySaverMode.evaluate(context)
        }
    }
}
