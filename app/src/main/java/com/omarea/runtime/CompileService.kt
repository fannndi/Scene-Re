package com.omarea.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.PowerManager.PARTIAL_WAKE_LOCK
import androidx.core.app.NotificationCompat
import com.omarea.common.shell.KeepShell
import com.omarea.common.shell.ShellLog
import com.omarea.util.CheckRootStatus
import com.omarea.vtools.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Background dex2oat runner with a foreground progress notification.
 *
 * Callers pass the exact package list (the apps selected in the app dialog,
 * or every 3rd-party package for Settings → JIT compile), so the UI never
 * blocks on the shell loop. A second [start] while a job runs cancels it.
 *
 * Responsibility: compile that list, publish progress, one job at a time.
 * Non-goals: choosing packages or flags (callers own that).
 */
class CompileService : Service() {

    enum class StartResult { QUEUED, CANCELLED, NO_ROOT, ERROR }

    companion object {
        private const val EXTRA_PACKAGES = "packages"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_FORCE = "force"
        private const val EXTRA_CANCEL = "cancel"
        private const val NOTIFICATION_ID = 990
        private const val CHANNEL_ID = "vtool-compile"

        /** True while a job runs (UI reads it to describe the state). */
        @Volatile
        var compiling = false
            private set

        /**
         * Queues a background compile — or sends a cancel for the job that is
         * already running. Never throws; the result says what happened.
         */
        fun start(context: Context, packages: Collection<String>, mode: String, force: Boolean): StartResult {
            // Monitor mode: `cmd package compile` needs a root shell.
            if (!CheckRootStatus.isAvailable()) return StartResult.NO_ROOT
            val cancelRequested = compiling
            val intent = Intent(context, CompileService::class.java).apply {
                if (cancelRequested) {
                    putExtra(EXTRA_CANCEL, true)
                } else {
                    putStringArrayListExtra(EXTRA_PACKAGES, ArrayList(packages))
                    putExtra(EXTRA_MODE, mode)
                    putExtra(EXTRA_FORCE, force)
                }
            }
            val result = dispatch(context, intent)
            return if (result == StartResult.QUEUED && cancelRequested) StartResult.CANCELLED else result
        }

        private fun dispatch(context: Context, intent: Intent): StartResult = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            StartResult.QUEUED
        } catch (ex: Exception) {
            ShellLog.log("CompileService.start", ex.message ?: "error", error = true)
            StartResult.ERROR
        }
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    @Volatile
    private var compileCanceled = false
    private val keepShell = KeepShell(true)
    private lateinit var nm: NotificationManager
    private var channelCreated = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serviceScope.launch { handleIntent(intent) }
        return START_NOT_STICKY
    }

    private fun handleIntent(intent: Intent?) {
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // The 5 s startForegroundService() contract holds for every path.
        startForegroundQuiet(getString(R.string.dex2oat_compiling), "")

        if (intent?.getBooleanExtra(EXTRA_CANCEL, false) == true) {
            compileCanceled = true
            if (!compiling) {
                finish(completed = false)
            }
            return
        }

        val packages = intent?.getStringArrayListExtra(EXTRA_PACKAGES).orEmpty()
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: "speed"
        val force = intent?.getBooleanExtra(EXTRA_FORCE, false) ?: false
        if (packages.isEmpty()) {
            finish(completed = false)
            return
        }
        if (compiling) {
            // Raced with a running job: cancel that one instead of stacking.
            compileCanceled = true
            return
        }

        compiling = true
        compileCanceled = false
        acquireWakeLock()

        val total = packages.size
        var current = 0
        for (pkg in packages) {
            if (compileCanceled) break
            updateNotification(
                getString(R.string.dex2oat_compiling) + " [$mode]",
                "[$current/$total] $pkg",
                total,
                current
            )
            keepShell.doCmdSync(
                "cmd package compile${if (force) " -f" else ""} -m $mode $pkg"
            )
            current++
        }

        keepShell.tryExit()
        compiling = false
        finish(completed = !compileCanceled)
    }

    // ---------------------------------------------------------- notification
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !channelCreated) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Background compile", NotificationManager.IMPORTANCE_LOW)
            )
            channelCreated = true
        }
    }

    private fun base(title: String, text: String): NotificationCompat.Builder {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.process)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
    }

    /** Satisfies the startForegroundService() contract on every entry path. */
    private fun startForegroundQuiet(title: String, text: String) {
        try {
            val notification = base(title, text).build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (ex: Exception) {
            ShellLog.log("CompileService.foreground", ex.message ?: "error", error = true)
        }
    }

    private fun updateNotification(title: String, text: String, total: Int, current: Int) {
        try {
            nm.notify(
                NOTIFICATION_ID,
                base(title, text)
                    .setProgress(total, current, false)
                    .setContentIntent(cancelIntent())
                    .build()
            )
        } catch (ex: Exception) {
            ShellLog.log("CompileService.notify", ex.message ?: "error")
        }
    }

    /** Cancel button: stops the running job (no-op when nothing runs). */
    private fun cancelIntent(): PendingIntent? = try {
        val intent = Intent(this, CompileService::class.java).putExtra(EXTRA_CANCEL, true)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, 1, intent, flags)
        } else {
            PendingIntent.getService(this, 1, intent, flags)
        }
    } catch (_: Exception) {
        null
    }

    private fun finish(completed: Boolean) {
        try {
            stopForegroundCompat()
            if (completed) {
                nm.notify(
                    NOTIFICATION_ID,
                    NotificationCompat.Builder(this, CHANNEL_ID)
                        .setSmallIcon(R.drawable.process)
                        .setContentTitle("complete!")
                        .setContentText(getString(R.string.dex2oat_completed))
                        .setAutoCancel(true)
                        .setProgress(0, 0, false)
                        .build()
                )
            } else {
                nm.cancel(NOTIFICATION_ID)
            }
        } catch (ex: Exception) {
            ShellLog.log("CompileService.finish", ex.message ?: "error")
        }
        releaseWakeLock()
        stopSelf()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    // --------------------------------------------------------------- wakelock
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PARTIAL_WAKE_LOCK, "scene:CompileService").apply {
                acquire(60 * 60 * 1000L) // hard cap: one hour
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        compiling = false
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
