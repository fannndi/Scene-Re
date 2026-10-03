package com.omarea.runtime

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.omarea.Scene
import com.omarea.data.EventType
import com.omarea.data.GlobalStatus
import com.omarea.data.IEventReceiver
import com.omarea.data.SpfConfig
import com.omarea.util.CheckRootStatus
import com.omarea.util.RootState
import com.omarea.util.battery.BatterySampler
import com.omarea.vtools.R

/**
 * 常驻通知
 */
internal class AlwaysNotification(
        private var context: Context,
        notify: Boolean = false,
        override val isAsync: Boolean = false) : ModeSwitcher(), IEventReceiver {
    override fun eventFilter(eventType: EventType): Boolean {
        return when (eventType) {
            EventType.SCENE_MODE_ACTION,
            EventType.BATTERY_CHANGED,
            EventType.BATTERY_CAPACITY_CHANGED,
            EventType.POWER_CONNECTED,
            EventType.POWER_DISCONNECTED -> true
            else -> false
        }
    }

    override fun onReceive(eventType: EventType, data: HashMap<String, Any>?) {
        if (eventType == EventType.SCENE_MODE_ACTION) {
            notify()
        } else if (showNofity) {
            notify()
        }
    }

    override fun onSubscribe() {

    }

    override fun onUnsubscribe() {

    }

    private var showNofity: Boolean = false
    private var notification: Notification? = null
    private var notificationManager: NotificationManager? = null
    private var globalSPF = context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)

    private val handler = Handler(Looper.getMainLooper())

    /** Keeps the notification values fresh (they used to lag behind stale globals). */
    private val refresher = object : Runnable {
        override fun run() {
            if (!showNofity) return
            runCatching { notify() }
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    private fun getAppName(packageName: String): CharSequence? {
        try {
            val appInfo = context.packageManager.getPackageInfo(packageName, 0).applicationInfo
            return appInfo?.loadLabel(context.packageManager) ?: packageName
        } catch (ex: Exception) {
            return packageName
        }
    }

    private fun getBatteryIcon(capacity: Int): Int {
        if (capacity < 20)
            return R.drawable.b_0
        if (capacity < 30)
            return R.drawable.b_1
        if (capacity < 70)
            return R.drawable.b_2

        return R.drawable.b_3
    }

    //显示通知
    internal fun notify() {
        try {
            // Raw id drives the icon; the display name is engine-OFF aware.
            val currentMode = getCurrentPowerMode()
            val currentModeName = getCurrentPowerModeName()
            val currentApp = getCurrentPowermodeApp().ifEmpty { "android" }
            notifyPowerModeChange(currentApp, currentMode, currentModeName)
        } catch (ex: Exception) {
        }
    }

    private fun notifyPowerModeChange(packageName: String, mode: String, modeName: String) {
        if (!showNofity) {
            return
        }

        // Monitor mode: no root -> the status cell explains WHY nothing runs.
        val monitor = !CheckRootStatus.isAvailable()
        val restorePending = !monitor && NoRootMode.isRestorePending(context)

        var batteryMa = 0
        var batteryValid = false
        var batteryTemp = 0.0
        try {
            val reading = BatterySampler.sample(context)
            batteryMa = reading.currentMa
            batteryValid = reading.valid
            batteryTemp = GlobalStatus.updateBatteryTemperature()
        } catch (ex: Exception) {
        }

        val remoteViews = getRemoteViews()
        remoteViews.setTextViewText(R.id.notify_title, getAppName(packageName))

        if (monitor) {
            val reason = if (CheckRootStatus.currentRootState() == RootState.MISSING) {
                context.getString(R.string.notification_monitor_reason_missing)
            } else {
                context.getString(R.string.notification_monitor_reason_denied)
            }
            remoteViews.setTextViewText(
                R.id.notify_text,
                context.getString(R.string.notification_monitor_sub) + " ($reason)"
            )
            remoteViews.setTextColor(R.id.notify_text, COLOR_AMBER)
            remoteViews.setTextViewText(
                R.id.notify_battery_title,
                context.getString(R.string.notification_status)
            )
            remoteViews.setTextColor(R.id.notify_battery_title, COLOR_AMBER)
            remoteViews.setTextViewText(
                R.id.notify_battery_text,
                context.getString(R.string.notification_monitor_mode)
            )
            remoteViews.setTextViewText(
                R.id.notify_battery_text2,
                context.getString(R.string.notification_monitor_hint_fmt, reason)
            )
            remoteViews.setTextColor(R.id.notify_battery_text2, COLOR_AMBER)
        } else {
            val state = NotificationFormat.batteryState(GlobalStatus.batteryStatus)
            val titleRes = when (state) {
                NotificationFormat.BatteryState.CHARGING -> R.string.notification_charging
                NotificationFormat.BatteryState.DISCHARGING -> R.string.notification_discharging
                NotificationFormat.BatteryState.FULL -> R.string.notification_full
                NotificationFormat.BatteryState.NOT_CHARGING -> R.string.notification_not_charging
                else -> R.string.notification_battery
            }
            remoteViews.setTextViewText(R.id.notify_text, modeName)
            remoteViews.setTextViewText(R.id.notify_battery_title, context.getString(titleRes))
            remoteViews.setTextViewText(
                R.id.notify_battery_text,
                NotificationFormat.capacityLine(GlobalStatus.batteryCapacity, batteryTemp)
            )
            val watts = NotificationFormat.formatWatts(
                GlobalStatus.batteryVoltage,
                if (batteryValid) batteryMa else 0
            )
            remoteViews.setTextViewText(
                R.id.notify_battery_text2,
                NotificationFormat.currentLine(batteryMa, batteryValid, watts)
            )
            if (state == NotificationFormat.BatteryState.CHARGING ||
                state == NotificationFormat.BatteryState.FULL
            ) {
                remoteViews.setTextColor(R.id.notify_battery_title, COLOR_GREEN)
            }
        }

        val clickIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, ReceiverSceneMode::class.java).putExtra("packageName", packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val icon = getModIcon(mode)
        notificationManager = context.getSystemService(Service.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (notificationManager!!.getNotificationChannel("vtool-long-time") == null) {
                notificationManager!!.createNotificationChannel(NotificationChannel("vtool-long-time", "Permanent Notice", NotificationManager.IMPORTANCE_LOW))
            }
        }
        val builder = NotificationCompat.Builder(context, "vtool-long-time")
                .setSmallIcon(icon)
                .setCustomContentView(remoteViews)
                .setCustomBigContentView(remoteViews)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setWhen(System.currentTimeMillis())
                .setAutoCancel(true)
                .setOngoing(false)
                .setContentIntent(clickIntent)

        // Shade-side twin of the Home restore card: appears only when root is
        // back after a Monitor-mode boot and the engine was auto-disabled.
        if (restorePending) {
            val enableIntent = PendingIntent.getBroadcast(
                context,
                1,
                Intent(context, ReceiverSceneAction::class.java)
                    .setAction(ReceiverSceneAction.ACTION_ENABLE_ENGINE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                0,
                context.getString(R.string.notification_enable_engine),
                enableIntent
            )
        }

        notification = builder.build()
        notification!!.flags = Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE
        notificationManager?.notify(0x100, notification)
    }

    private fun getRemoteViews(): RemoteViews {
        return RemoteViews(context.packageName, R.layout.layout_notification)
    }

    //隐藏通知
    internal fun hideNotify() {
        handler.removeCallbacks(refresher)
        if (notification != null) {
            notificationManager?.cancel(0x100)
            notification = null
            notificationManager = null
        }
    }

    internal fun setNotify(show: Boolean) {
        this.showNofity = show
        if (!show) {
            hideNotify()
        } else {
            handler.removeCallbacks(refresher)
            handler.post(refresher)
        }
    }

    private companion object {
        const val REFRESH_MS = 5000L

        /** Charging accent (green) / Monitor accent (amber) for RemoteViews. */
        val COLOR_GREEN = 0xFF4CAF50.toInt()
        val COLOR_AMBER = 0xFFFFB300.toInt()
    }

    init {
        showNofity = notify
    }
}
