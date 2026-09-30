package com.omarea.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import com.omarea.common.shell.ShellLog
import com.omarea.engine.ThermalController.State
import java.io.File

/**
 * Always-on thermal guard — the Kotlin replacement for `assets/scene_thermald.sh`.
 *
 * Lifecycle: started/stopped ONLY by [com.omarea.engine.DaemonController],
 * which follows the profile-engine state (engine ON → running, OFF → gone).
 *
 * Every 5s it reads the battery temperature (sticky broadcast, no shell),
 * runs [ThermalController.decide] and then:
 *   - hot   → lowers scaling_max to the state limit (only ever LOWER;
 *             the live value is read back first, never raised);
 *   - cool  → restores the profile max handed over by ProfileApplier.
 * Direct sysfs writes are used when the opt-in SELinux direct-write mode is
 * on; otherwise every write falls back to the root shell.
 * Kernel thermal trips remain the final safety net.
 *
 * Responsibility: the loop, node I/O, foreground notification.
 * Non-goals: threshold policy ([ThermalController]), profile values.
 */
class ThermalService : Service() {

    private var worker: Thread? = null

    @Volatile
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        running = true
        startGuardedForeground()
        worker = Thread({ loop() }, "scene-thermal").apply {
            isDaemon = true
            start()
        }
        ShellLog.log("ThermalService", "started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        worker = null
        isRunning = false
        ShellLog.log("ThermalService", "stopped")
        super.onDestroy()
    }

    // ------------------------------------------------------------------ loop
    private fun loop() {
        var prev: State? = readPersistedState()
        while (running && !Thread.currentThread().isInterrupted) {
            try {
                // External stop file honoured (same protocol as the shell daemon).
                if (File(ShellNodes.THERMALD_STOP).exists()) {
                    runCatching { File(ShellNodes.THERMALD_STOP).delete() }
                    ShellLog.log("ThermalService", "stop file found -> exiting")
                    break
                }

                val tempDeci = readBatteryTempDeci()
                if (tempDeci == null) {
                    sleepQuietly(INTERVAL_MS)
                    continue
                }
                val state = ThermalController.decide(tempDeci, prev)
                if (state != prev) {
                    ShellLog.log(
                        "ThermalService",
                        "TEMP ${tempDeci / 10}C: ${prev?.fileValue ?: "init"} -> ${state.fileValue}" +
                            (if (state.isClamped) ", clamp ${state.limitKhz}" else ", restoring profile max")
                    )
                    persistState(state)
                }

                if (state.isClamped) {
                    clampTo(state.limitKhz)
                } else if (ThermalController.shouldRestore(state, prev)) {
                    restoreProfileMax()
                }
                prev = state
            } catch (ex: Exception) {
                ShellLog.log("ThermalService.loop", ex.message ?: "error", error = true)
            }
            sleepQuietly(INTERVAL_MS)
        }
        // Leaving the clamped state on shutdown would hide a hot device;
        // profile re-apply / engine OFF owns the restore instead.
        stopSelf()
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            // stop requested
        }
    }

    // ------------------------------------------------------------------ nodes
    /** Battery temperature in deci-Celsius via sticky broadcast (no shell). */
    private fun readBatteryTempDeci(): Int? = try {
        val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it > 0 }
    } catch (ex: Exception) {
        null
    }

    /** Reads the live scaling_max for [policy]; direct first, shell fallback. */
    private fun readMax(policy: Int): Long? {
        val node = "/sys/devices/system/cpu/cpufreq/policy$policy/scaling_max_freq"
        try {
            val text = File(node).readText().trim()
            if (text.isNotEmpty()) return text.toLongOrNull()
        } catch (_: Exception) {
            // fall through to the root shell
        }
        return RootShell.read(node).toLongOrNull()
    }

    /** Only ever lowers: writes [limit] when the live max sits above it. */
    private fun clampTo(limit: Long) {
        for (policy in POLICIES) {
            val live = readMax(policy) ?: continue
            if (live > limit) {
                writeNode("/sys/devices/system/cpu/cpufreq/policy$policy/scaling_max_freq", limit.toString())
            }
        }
    }

    /** Writes the active profile's max back (may raise — that's the point). */
    private fun restoreProfileMax() {
        val raw = readProfileMax() ?: return
        val parts = raw.trim().split(Regex("\\s+"))
        val pm0 = parts.getOrNull(0)?.toLongOrNull()
        val pm6 = parts.getOrNull(1)?.toLongOrNull()
        if (pm0 != null && pm0 > 0) {
            writeNode("/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq", pm0.toString(), raise = true)
        }
        if (pm6 != null && pm6 > 0) {
            writeNode("/sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq", pm6.toString(), raise = true)
        }
    }

    /**
     * Direct write when the opt-in mode allows it, root shell otherwise.
     * [raise] must be true only for profile restores — the clamp path never
     * raises (hard rule: this guard only lowers scaling_max).
     */
    private fun writeNode(node: String, value: String, raise: Boolean = false) {
        if (directWrites && DirectWrite.write(node, value)) return
        val verb = if (raise) {
            "chmod 0664 '$node' 2>/dev/null; echo '$value' > '$node' 2>/dev/null"
        } else {
            "cur=\$(cat '$node' 2>/dev/null); " +
                "if [ -n \"\$cur\" ] && [ \"\$cur\" -gt '$value' ] 2>/dev/null; then " +
                "chmod 0664 '$node' 2>/dev/null; echo '$value' > '$node' 2>/dev/null; fi"
        }
        RootShell.run(verb)
    }

    private fun readProfileMax(): String? = try {
        val file = File(ShellNodes.THERMALD_PROFILE_MAX)
        if (file.canRead()) file.readText() else RootShell.run("cat ${ShellNodes.THERMALD_PROFILE_MAX} 2>/dev/null")
            .trim().takeIf { it.isNotEmpty() }
    } catch (ex: Exception) {
        null
    }

    // ------------------------------------------------------------------ state
    /** Persisted state survives service restarts (mirror of the state file). */
    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readPersistedState(): State? =
        ThermalController.parseState(prefs().getString(KEY_STATE, null))

    private fun persistState(state: State) {
        prefs().edit().putString(KEY_STATE, state.fileValue).apply()
        // Keep the legacy file in sync for DiagnosticsCollector/scene-debug.
        runCatching { RootShell.run("echo '${state.fileValue}' > ${ShellNodes.THERMALD_STATE} 2>/dev/null") }
    }

    private val directWrites: Boolean
        get() = SepolicyOptimizer.directWritesEnabled(this)

    // ---------------------------------------------------------- notification
    private fun startGuardedForeground() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, getString(com.omarea.vtools.R.string.notice_channel_thermal),
                        NotificationManager.IMPORTANCE_LOW)
                )
            }
            val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
                .setSmallIcon(com.omarea.vtools.R.drawable.ic_menu_digital)
                .setContentTitle(getString(com.omarea.vtools.R.string.notice_channel_thermal))
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (ex: Exception) {
            // Background start restriction: START_STICKY still keeps the loop
            // alive while the app process lives; DaemonController falls back
            // to the bundled shell daemon when the service can't start at all.
            ShellLog.log("ThermalService.foreground", ex.message ?: "error", error = true)
        }
    }

    companion object {
        private const val INTERVAL_MS = 5000L
        private const val NOTIFICATION_ID = 910
        private const val CHANNEL_ID = "vtool-thermal"
        private const val PREFS = "scene_thermal"
        private const val KEY_STATE = "state"
        private val POLICIES = intArrayOf(0, 6)

        @Volatile
        var isRunning: Boolean = false
            private set

        /** Starts the guard if it isn't already alive. False → caller falls back. */
        fun start(context: Context): Boolean {
            if (isRunning) return true
            return try {
                val intent = Intent(context, ThermalService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                isRunning = true
                true
            } catch (ex: Exception) {
                ShellLog.log("ThermalService.start", ex.message ?: "error", error = true)
                false
            }
        }

        fun stop(context: Context) {
            if (!isRunning) return
            try {
                context.stopService(Intent(context, ThermalService::class.java))
            } catch (ex: Exception) {
                ShellLog.log("ThermalService.stop", ex.message ?: "error", error = true)
            }
            isRunning = false
        }
    }
}
