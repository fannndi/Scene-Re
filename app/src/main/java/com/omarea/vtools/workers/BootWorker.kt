package com.omarea.vtools.workers

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.omarea.common.shared.RawText
import com.omarea.common.shell.KeepShell
import com.omarea.data.EventBus
import com.omarea.data.EventType
import com.omarea.library.shell.BatteryUtils
import com.omarea.library.shell.PropsUtils
import com.omarea.scene_mode.ModeSwitcher
import com.omarea.scene_mode.SceneMode
import com.omarea.store.CpuConfigStorage
import com.omarea.store.SceneConfigStore
import com.omarea.store.SpfConfig
import com.omarea.utils.CommonCmds
import com.omarea.utils.ShellSafety
import com.omarea.vtools.R

class BootWorker(
    private val appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {
    companion object {
        private const val NOTIFICATION_ID = 900
        private const val CHANNEL_ID = "vtool-boot"
    }

    private lateinit var globalConfig: SharedPreferences
    private var isFirstBoot = true
    private var bootCancel = false
    private lateinit var nm: NotificationManager
    private var channelCreated = false
    private var foregroundStarted = false

    override fun doWork(): Result {
        nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        globalConfig = appContext.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)

        if (globalConfig.getBoolean(SpfConfig.GLOBAL_SPF_START_DELAY, false)) {
            Thread.sleep(25 * 1000L)
        } else {
            Thread.sleep(2000L)
        }
        val r = PropsUtils.getProp("vtools.boot")
        if (r.isNotEmpty()) {
            isFirstBoot = false
            bootCancel = true
            hideNotification()
            return Result.success()
        }

        setForegroundNotice(appContext.getString(R.string.boot_script_running))
        EventBus.publish(EventType.BOOT_COMPLETED)
        autoBoot()
        return Result.success()
    }

    private fun autoBoot() {
        val keepShell = KeepShell()

        try {
            if (globalConfig.getBoolean(SpfConfig.GLOBAL_SPF_DISABLE_ENFORCE, false)) {
                keepShell.doCmdSync(CommonCmds.DisableSELinux)
            }

            val cpuConfigStorage = CpuConfigStorage(appContext)
            val cpuState = cpuConfigStorage.load()
            if (cpuState != null) {
                updateNotification(appContext.getString(R.string.boot_cpuset))
                cpuConfigStorage.applyCpuConfig(cpuConfigStorage.default())
            }

            val macChangeMode = globalConfig.getInt(SpfConfig.GLOBAL_SPF_MAC_AUTOCHANGE_MODE, 0)
            val mac = globalConfig.getString(SpfConfig.GLOBAL_SPF_MAC, "")
            if (macChangeMode != 0 && !mac.isNullOrEmpty()) {
                if (!ShellSafety.isValidMac(mac)) {
                    // Invalid MAC address: skip silently to avoid any command injection
                } else {
                    when (macChangeMode) {
                        SpfConfig.GLOBAL_SPF_MAC_AUTOCHANGE_MODE_1 -> {
                            updateNotification(appContext.getString(R.string.boot_modify_mac))
                            keepShell.doCmdSync("mac=${ShellSafety.quote(mac)}\n" + RawText.getRawText(appContext, R.raw.change_mac_1))
                        }
                        SpfConfig.GLOBAL_SPF_MAC_AUTOCHANGE_MODE_2 -> {
                            updateNotification(appContext.getString(R.string.boot_modify_mac))
                            keepShell.doCmdSync("mac=${ShellSafety.quote(mac)}\n" + RawText.getRawText(appContext, R.raw.change_mac_2))
                        }
                    }
                }
            }

            val chargeConfig = appContext.getSharedPreferences(SpfConfig.CHARGE_SPF, Context.MODE_PRIVATE)
            if (chargeConfig.getBoolean(SpfConfig.CHARGE_SPF_QC_BOOSTER, false) || chargeConfig.getBoolean(SpfConfig.CHARGE_SPF_BP, false)) {
                updateNotification(appContext.getString(R.string.boot_charge_booster))
                BatteryUtils().setChargeInputLimit(
                    chargeConfig.getInt(SpfConfig.CHARGE_SPF_QC_LIMIT, SpfConfig.CHARGE_SPF_QC_LIMIT_DEFAULT),
                    appContext
                )
            }

            val globalPowercfg = globalConfig.getString(SpfConfig.GLOBAL_SPF_POWERCFG, "")
            if (!globalPowercfg.isNullOrEmpty()) {
                updateNotification(appContext.getString(R.string.boot_use_powercfg))

                val modeSwitcher = ModeSwitcher()
                if (modeSwitcher.modeConfigCompleted()) {
                    modeSwitcher.executePowercfgMode(globalPowercfg, appContext.packageName)
                }
            }

            updateNotification(appContext.getString(R.string.boot_freeze))
            val launchedFreezeApp = SceneMode.getCurrentInstance()?.getLaunchedFreezeApp()
            val suspendMode = globalConfig.getBoolean(SpfConfig.GLOBAL_SPF_FREEZE_SUSPEND, Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            for (item in SceneConfigStore(appContext).freezeAppList) {
                if (launchedFreezeApp == null || !launchedFreezeApp.contains(item)) {
                    if (suspendMode) {
                        SceneMode.suspendApp(item)
                    } else {
                        SceneMode.freezeApp(item)
                    }
                }
            }
        } finally {
            keepShell.tryExit()
            hideNotification()
        }
    }

    private fun setForegroundNotice(text: String) {
        if (!foregroundStarted) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
            setForegroundAsync(ForegroundInfo(NOTIFICATION_ID, buildNotification(text), type))
            foregroundStarted = true
        } else {
            updateNotification(text)
        }
    }

    private fun updateNotification(text: String) {
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun hideNotification() {
        if (bootCancel) {
            nm.cancel(NOTIFICATION_ID)
        } else {
            updateNotification(appContext.getString(R.string.boot_success))
        }
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !channelCreated) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, appContext.getString(R.string.notice_channel_boot), NotificationManager.IMPORTANCE_LOW))
            channelCreated = true
        }
        return NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_menu_digital)
            .setContentTitle(appContext.getString(R.string.notice_channel_boot))
            .setContentText(text)
            .build()
    }
}
