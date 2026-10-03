package com.omarea.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.omarea.ui.popup.FloatPowercfgSelector
import com.omarea.util.CheckRootStatus
import com.omarea.vtools.R

class ReceiverSceneMode : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.extras != null) {
            val parameterValue = intent.getStringExtra("packageName");
            if (parameterValue == null || parameterValue.isEmpty()) {
                return
            }
            // Monitor mode: the mode picker would be a lie (nothing can be
            // applied) — open Diagnostics, which explains the state.
            if (!CheckRootStatus.isAvailable()) {
                Toast.makeText(
                    context,
                    context.getString(R.string.notification_monitor_sub),
                    Toast.LENGTH_SHORT
                ).show()
                context.startActivity(
                    Intent(context, com.omarea.ui.activity.ActivityDiagnostics::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return
            }
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context)) {
                // Defer to the trampoline activity: it shows the in-app mode
                // picker and the *real* permission screen (this path used to
                // stop at a toast while the intent was never started).
                context.startActivity(
                    Intent(context, com.omarea.ui.activity.ActivityPowerModeTile::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } else {
                FloatPowercfgSelector(context.applicationContext).open(parameterValue)
            }
        }
    }
}
