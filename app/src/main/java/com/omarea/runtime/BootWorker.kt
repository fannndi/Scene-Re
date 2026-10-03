package com.omarea.runtime

import android.app.ActivityManager
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
import com.omarea.common.shell.KeepShell
import com.omarea.common.shell.KernelProrp
import com.omarea.data.EventBus
import com.omarea.data.EventType
import com.omarea.util.LMKUtils
import com.omarea.util.PropsUtils
import com.omarea.util.SwapUtils
import com.omarea.runtime.SceneMode
import com.omarea.data.SceneConfigStore
import com.omarea.data.SpfConfig
import com.omarea.util.CommonCmds
import com.omarea.vtools.R

class BootWorker(
    private val appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {
    companion object {
        private const val NOTIFICATION_ID = 900
        private const val CHANNEL_ID = "vtool-boot"
    }

    private lateinit var swapConfig: SharedPreferences
    private lateinit var globalConfig: SharedPreferences
    private var isFirstBoot = true
    private var bootCancel = false
    private lateinit var nm: NotificationManager
    private var channelCreated = false
    private var foregroundStarted = false

    override fun doWork(): Result {
        nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        swapConfig = appContext.getSharedPreferences(SpfConfig.SWAP_SPF, Context.MODE_PRIVATE)
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

        // TRUE OFF: nothing is re-applied at boot — no profile, no SELinux
        // op, no cpuset, no triggers (swap/zRAM state is left exactly as-is).
        if (!TrueOff.allowsWrite(appContext)) {
            hideNotification()
            return Result.success()
        }

        // Mode Monitor: tanpa root setiap writer adalah no-op. Jangan jalankan
        // autoBoot (spam shell su mati + bukti "applied" palsu) — matikan
        // engine, tulis bukti jujur, dan beri tahu user.
        val rootState = com.omarea.util.CheckRootStatus.checkRootQuietly()
        if (rootState != com.omarea.util.RootState.AVAILABLE) {
            com.omarea.runtime.NoRootMode.onRootUnavailable(appContext, rootState)
            nm.cancel(NOTIFICATION_ID)
            return Result.success()
        }

        // Rooted boot: (re)provision the uninstall guard. Its service.sh
        // restores PM/settings + removes every Scene module if the app is ever
        // uninstalled without a clean release.
        runCatching { com.omarea.runtime.SceneGuard.sync(appContext) }

        setForegroundNotice(appContext.getString(R.string.boot_script_running))
        EventBus.publish(EventType.BOOT_COMPLETED)
        autoBoot()
        return Result.success()
    }

    private fun autoBoot() {
        val keepShell = KeepShell()

        // MIUI resets the overlay appop (SYSTEM_ALERT_WINDOW -> ignore) at
        // every boot, which silently kills the quick-switch popup and float
        // windows. Self-heal our own permission while we hold root here.
        keepShell.doCmdSync("appops set ${appContext.packageName} SYSTEM_ALERT_WINDOW allow")

        // Legacy charge-control artifacts: charging is read-only now, undo
        // whatever earlier Scene versions persisted (no-op when clean).
        keepShell.doCmdSync(ChargeStockRestorer.command())

        if (globalConfig.getBoolean(SpfConfig.GLOBAL_SPF_DISABLE_ENFORCE, false)) {
            keepShell.doCmdSync(CommonCmds.DisableSELinux)
        }

        // Re-apply the active device profile after boot (unless profiles are OFF)
        if (!globalConfig.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_OFF, false)) {
            try {
                updateNotification(appContext.getString(R.string.boot_profile))
                com.omarea.engine.KernelCompat.refresh(appContext)
                com.omarea.engine.SepolicyOptimizer.apply(appContext)
                // The ROM's qcom-post-boot (late_start oneshot triggered by
                // sys.boot_completed) writes the same node families we do.
                // Wait for it to finish so it cannot overwrite our apply.
                val waited = RomBootGate.await(appContext)
                PostApplyDriftGuard.storePostBootWait(appContext, waited)
                // init + saved mode + daemons; keeps prop/pref in sync so the
                // UI, notification and HWUI all see the restored mode.
                ModeSwitcher().applyBootState()
                // Boot evidence for the MIUI autostart warning.
                PostApplyDriftGuard.markBootApplied(appContext)
                // Safety net: re-apply once when something rewrites the nodes
                // after us (late ROM services, vendor daemons).
                PostApplyDriftGuard.schedule(appContext)
            } catch (ex: Exception) {
                // non-fatal: mode re-applies on next app open
            }
        }

        // Battery-saver overlay: derive the post-boot truth (overlay state
        // from the previous boot is stale). No-op on engine OFF/TRUE OFF.
        runCatching { BatterySaverMode.evaluateAtBoot(appContext) }

        // Bypass charging: kernel resets the node on boot; derive the truth.
        runCatching { BypassCharging.evaluateAtBoot(appContext) }

        // Opt-in extras: re-apply after boot when enabled.
        runCatching { if (LoggingReduction.isEnabled(appContext)) LoggingReduction.apply(appContext) }
        runCatching { if (KernelCrashGuard.isEnabled(appContext)) KernelCrashGuard.apply(appContext) }
        runCatching { if (SfFramePacing.isEnabled(appContext)) SfFramePacing.apply(appContext) }
        runCatching { if (IrqAffinity.isEnabled(appContext)) IrqAffinity.apply(appContext) }
        runCatching { if (RootForegroundWatch.isEnabled(appContext)) RootForegroundWatch.start(appContext) }

        // Fresh per-boot direct-write capability probe: fills the in-memory
        // cache used by ProfileApplier and writes the pullable report
        // (files/debug/sepolicy-caps.txt) for diagnostics/agents.
        runCatching {
            com.omarea.engine.SepolicyCapability.probeAll(appContext)
        }

        // NB: no charge limit is applied at boot — charging is read-only by
        // policy; the ROM/kernel owns every charge parameter.

        if (!keepShell.doCmdSync("getprop vtools.swap.controller").equals("magisk")) {
            if (swapConfig.getBoolean(SpfConfig.SWAP_SPF_SWAP, false)) {
                enableSwap(keepShell, appContext)
            }

            if (swapConfig.getBoolean(SpfConfig.SWAP_SPF_ZRAM, false)) {
                val sizeVal = swapConfig.getInt(SpfConfig.SWAP_SPF_ZRAM_SIZE, 0)
                val algorithm = swapConfig.getString(SpfConfig.SWAP_SPF_ALGORITHM, "")

                updateNotification(appContext.getString(R.string.boot_resize_zram))
                resizeZram(sizeVal, algorithm ?: "", keepShell, true)
            }

            if (swapConfig.contains(SpfConfig.SWAP_SPF_SWAPPINESS)) {
                keepShell.doCmdSync("echo 65 > /proc/sys/vm/swappiness\n")
                keepShell.doCmdSync("echo " + swapConfig.getInt(SpfConfig.SWAP_SPF_SWAPPINESS, 65) + " > /proc/sys/vm/swappiness\n")
            }

            if (swapConfig.contains(SpfConfig.SWAP_SPF_EXTRA_FREE_KBYTES)) {
                keepShell.doCmdSync("echo ${swapConfig.getInt(SpfConfig.SWAP_SPF_EXTRA_FREE_KBYTES, 29615)} > /proc/sys/vm/extra_free_kbytes\n")
            }

            if (swapConfig.contains(SpfConfig.SWAP_SPF_WATERMARK_SCALE)) {
                keepShell.doCmdSync("echo ${swapConfig.getInt(SpfConfig.SWAP_SPF_WATERMARK_SCALE, 100)} > /proc/sys/vm/watermark_scale_factor\n")
            }

            if (swapConfig.getBoolean(SpfConfig.SWAP_SPF_AUTO_LMK, false)) {
                updateNotification(appContext.getString(R.string.boot_lmk))

                val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val info = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(info)
                LMKUtils().autoSetLMK(info.totalMem, keepShell)
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

        keepShell.tryExit()
        hideNotification()
    }

    private var compAlgorithm: String
        get() {
            val compAlgorithmItems = KernelProrp.getProp("/sys/block/zram0/comp_algorithm").split(" ")
            val result = compAlgorithmItems.find {
                it.startsWith("[") && it.endsWith("]")
            }
            if (result != null) {
                return result.replace("[", "").replace("]", "").trim()
            }
            return ""
        }
        set(value) {
            KernelProrp.setProp("/sys/block/zram0/comp_algorithm", value)
        }

    private fun enableSwap(keepShell: KeepShell, context: Context) {
        updateNotification(appContext.getString(R.string.boot_swapon))
        val swapPriority = swapConfig.getInt(SpfConfig.SWAP_SPF_SWAP_PRIORITY, -2)
        val useLoop = swapConfig.getBoolean(SpfConfig.SWAP_SPF_SWAP_USE_LOOP, false)
        SwapUtils(context).swapOn(swapPriority, useLoop, keepShell)
    }

    private fun resizeZram(sizeVal: Int, algorithm: String = "", keepShell: KeepShell, swapFirst: Boolean = false) {
        keepShell.doCmdSync(
            "if [[ ! -e /dev/block/zram0 ]] && [[ -e /sys/class/zram-control ]]; then\n" +
                "  cat /sys/class/zram-control/hot_add\n" +
                "fi"
        )
        val currentSize = keepShell.doCmdSync("cat /sys/block/zram0/disksize")
        if (currentSize != "" + (sizeVal * 1024 * 1024L) || (algorithm.isNotEmpty() && algorithm != compAlgorithm)) {
            val sb = StringBuilder()
            sb.append("swappiness_bak=`cat /proc/sys/vm/swappiness`\n")
            if (!swapFirst) {
                sb.append("echo 0 > /proc/sys/vm/swappiness\n")
            }

            sb.append("echo 4 > /sys/block/zram0/max_comp_streams\n")
            sb.append("sync\n")

            sb.append("if [[ -f /sys/block/zram0/backing_dev ]]; then\n")
            sb.append("  backing_dev=$(cat /sys/block/zram0/backing_dev)\n")
            sb.append("fi\n")

            sb.append("echo 3 > /proc/sys/vm/drop_caches\n")
            sb.append("swapoff /dev/block/zram0 >/dev/null 2>&1\n")
            sb.append("echo 1 > /sys/block/zram0/reset\n")

            sb.append("if [[ -f /sys/block/zram0/backing_dev ]]; then\n")
            sb.append("  echo \"\$backing_dev\" > /sys/block/zram0/backing_dev\n")
            sb.append("fi\n")

            if (algorithm.isNotEmpty()) {
                sb.append("echo \"$algorithm\" > /sys/block/zram0/comp_algorithm\n")
            }

            if (sizeVal > 2047) {
                sb.append("echo " + sizeVal + "M > /sys/block/zram0/disksize\n")
            } else {
                sb.append("echo " + (sizeVal * 1024 * 1024L) + " > /sys/block/zram0/disksize\n")
            }

            sb.append("echo 4 > /sys/block/zram0/max_comp_streams\n")
            sb.append("mkswap /dev/block/zram0 >/dev/null 2>&1\n")
            sb.append("swapon /dev/block/zram0 -p 0 >/dev/null 2>&1\n")
            sb.append("echo \$swappiness_bak > /proc/sys/vm/swappiness")
            keepShell.doCmdSync(sb.toString())
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
