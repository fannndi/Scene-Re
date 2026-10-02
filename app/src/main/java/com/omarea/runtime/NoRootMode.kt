package com.omarea.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.omarea.common.shell.ShellLog
import com.omarea.data.SpfConfig
import com.omarea.engine.ProfileController
import com.omarea.engine.StockSnapshot
import com.omarea.engine.ThermalService
import com.omarea.util.CheckRootStatus
import com.omarea.util.RootState
import com.omarea.vtools.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Monitor mode" — the no-root safety state.
 *
 * When root is unavailable nothing Scene writes can land. Instead of pretending
 * the engine is ON (the old behaviour left a ghost ThermalService running and
 * marked the boot as "applied"), this object:
 *
 *  1. stops the local guard service,
 *  2. auto-disables the engine pref (never while TRUE OFF owns the state) and
 *     sets [SpfConfig.GLOBAL_SPF_ENGINE_RESTORE_PENDING] so the user gets a
 *     one-tap restore once root is back,
 *  3. stores honest boot evidence for Diagnostics,
 *  4. always posts one notification with the real reason (missing vs denied).
 *
 * Responsibility: the no-root transition + evidence + notification.
 * Non-goals: feature gates (each writer checks `CheckRootStatus` itself).
 */
object NoRootMode {

    private const val PREFS = "scene_boot"
    private const val KEY_REASON = "no_root_reason"
    private const val KEY_AT = "no_root_at"
    private const val KEY_BOOT = "no_root_boot"
    private const val NOTIFICATION_ID = 911
    private const val CHANNEL_ID = "vtool-no-root"

    /** Called on every detection: boot quiet-check, app start, su-missing. */
    fun onRootUnavailable(context: Context, state: RootState) {
        val app = context.applicationContext

        // 1. Ghost guard: started by the old engine path, cannot write anyway.
        runCatching { ThermalService.stop(app) }

        // 2. Engine auto-OFF + restore prompt. TRUE OFF owns its own state
        //    (rule 14): never fight it.
        val prefs = app.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
        val engineWasOn = !ProfileController.isEngineOff(app)
        if (shouldAutoDisableEngine(engineWasOn, TrueOff.isOff(app))) {
            prefs.edit()
                .putBoolean(SpfConfig.GLOBAL_SPF_PROFILE_OFF, true)
                .putBoolean(SpfConfig.GLOBAL_SPF_ENGINE_RESTORE_PENDING, true)
                .apply()
        }

        // 3. Honest evidence: this boot did NOT get an apply.
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_REASON, state.name)
            .putLong(KEY_AT, System.currentTimeMillis())
            .putInt(KEY_BOOT, StockSnapshot.bootCount(app))
            .apply()

        // 4. Always tell the user (product decision: every boot without root).
        runCatching { notifyUser(app, state) }

        ShellLog.log("NoRootMode", "root=$state -> monitor mode (engineWasOn=$engineWasOn)")
    }

    fun isRestorePending(context: Context): Boolean =
        context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
            .getBoolean(SpfConfig.GLOBAL_SPF_ENGINE_RESTORE_PENDING, false)

    /**
     * Pure decision (JVM-tested): auto-disable the engine when it is ON and
     * TRUE OFF does not already own the state (rule 14).
     */
    internal fun shouldAutoDisableEngine(engineOn: Boolean, trueOff: Boolean): Boolean =
        engineOn && !trueOff

    fun clearRestorePending(context: Context) {
        context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
            .edit().putBoolean(SpfConfig.GLOBAL_SPF_ENGINE_RESTORE_PENDING, false).apply()
    }

    /** Diagnostics line: why this boot skipped tuning. Null when never happened. */
    fun lastEvidence(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val reason = prefs.getString(KEY_REASON, null) ?: return null
        val at = prefs.getLong(KEY_AT, 0L)
        val boot = prefs.getInt(KEY_BOOT, -1)
        val whenText = if (at > 0) {
            SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(at))
        } else "?"
        return "$reason · boot $boot · $whenText · no apply this boot"
    }

    private fun notifyUser(app: Context, state: RootState) {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    app.getString(R.string.notice_channel_no_root),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        val content = app.getString(R.string.home_no_root_warning)
        val text = if (state == RootState.MISSING) {
            app.getString(R.string.notif_no_root_missing)
        } else {
            app.getString(R.string.notif_no_root_denied)
        }

        val intent = Intent(app, com.omarea.ui.activity.ActivityStartSplash::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            app, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_menu_digital)
            .setContentTitle(content)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setOngoing(false)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }
}
