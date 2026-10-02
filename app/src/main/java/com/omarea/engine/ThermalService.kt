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
import com.omarea.runtime.TrueOff
import com.omarea.util.CheckRootStatus
import java.io.File

/**
 * Always-on thermal guard — the Kotlin replacement for `assets/scene_thermald.sh`.
 *
 * Lifecycle: started/stopped ONLY by [com.omarea.engine.DaemonController],
 * which follows the profile-engine state (engine ON → running, OFF → gone).
 *
 * Every 5s it reads the battery temperature (sticky broadcast, no shell),
 * runs [ThermalController.decide] and then:
 *   - hot   → lowers scaling_max to the state limit and moves the GPU to a
 *             slower pwrlevel (only ever slower; both are read back first,
 *             never raised);
 *   - cool  → restores the profile max and GPU levels handed over by
 *             [ProfileApplier].
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
        // Monitor mode: never run a guard that cannot write. START_STICKY can
        // resurrect this service after a process death — refuse cleanly.
        if (!CheckRootStatus.isAvailable()) {
            ShellLog.log("ThermalService", "start refused: no root (monitor mode)")
            stopSelf()
            return
        }
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
        var lastRawDeci: Int? = null
        var smoothedDeci: Double? = null
        var lastTickMs = 0L
        var clampStartDeci: Int? = null
        while (running && !Thread.currentThread().isInterrupted) {
            try {
                // Root lost mid-session (root manager removed): stop the guard
                // instead of spinning through dead shell calls every 5s.
                if (!CheckRootStatus.isAvailable()) {
                    ShellLog.log("ThermalService", "root lost -> stopping guard", error = true)
                    break
                }
                // External stop file honoured (same protocol as the shell daemon).
                if (File(ShellNodes.THERMALD_STOP).exists()) {
                    runCatching { File(ShellNodes.THERMALD_STOP).delete() }
                    ShellLog.log("ThermalService", "stop file found -> exiting")
                    break
                }

                val rawDeci = readBatteryTempDeci()
                if (rawDeci == null) {
                    sleepQuietly(INTERVAL_MS)
                    continue
                }
                // Reject sensor glitches (>10 C jump) and smooth the signal
                // (AZenith thermalcore): the state machine sees a stable value.
                if (ThermalController.isAnomaly(lastRawDeci, rawDeci)) {
                    ShellLog.log(
                        "ThermalService",
                        "temp anomaly ignored: ${lastRawDeci!! / 10}C -> ${rawDeci / 10}C"
                    )
                    sleepQuietly(INTERVAL_MS)
                    continue
                }
                lastRawDeci = rawDeci
                val prevSmoothed = smoothedDeci
                smoothedDeci = ThermalController.smooth(smoothedDeci, rawDeci)
                val tempDeci = Math.round(smoothedDeci).toInt()

                // Temperature trend (C/min) for the predictive pre-clamp.
                val nowMs = System.currentTimeMillis()
                val dtMin = if (lastTickMs > 0) {
                    ((nowMs - lastTickMs) / 60_000.0).coerceAtLeast(0.05)
                } else 0.0
                val slopePerMin = if (prevSmoothed != null && dtMin > 0.0) {
                    (smoothedDeci - prevSmoothed) / dtMin
                } else 0.0
                lastTickMs = nowMs

                val baseState = ThermalController.decide(tempDeci, prev)
                val state = ThermalController.withPreemption(baseState, tempDeci, slopePerMin)
                if (state != baseState) {
                    ShellLog.log(
                        "ThermalService",
                        "pre-emptive clamp: ${tempDeci / 10}C rising ${"%.1f".format(slopePerMin)}C/min -> ${state.fileValue}"
                    )
                }
                if (state != prev) {
                    ShellLog.log(
                        "ThermalService",
                        "TEMP ${tempDeci / 10}C (raw ${rawDeci / 10}C): ${prev?.fileValue ?: "init"} -> ${state.fileValue}" +
                            (if (state.isClamped) ", clamp ${state.limitKhz}" else ", restoring profile max")
                    )
                    persistState(state)
                }

                if (state.isClamped) {
                    if (prev == null || !prev.isClamped) clampStartDeci = tempDeci
                    clampTo(state.limitKhz)
                    clampGpu(state)
                } else if (ThermalController.shouldRestore(state, prev)) {
                    restoreProfileLimits()
                    clampStartDeci?.let { start ->
                        recordEffectiveness(start, tempDeci)
                        clampStartDeci = null
                    }
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
        sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it in 30..1200 }
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

    /** Reads an integer node (direct first, root shell fallback). */
    private fun readInt(node: String): Int? {
        try {
            val text = File(node).readText().trim()
            if (text.isNotEmpty()) return text.toIntOrNull()
        } catch (_: Exception) {
            // fall through to the root shell
        }
        return RootShell.read(node).trim().toIntOrNull()
    }

    /**
     * Soft GPU cooling, mirroring mi_thermald's devfreq cooling: while hot the
     * guard only ever moves the GPU to *slower* levels (higher pwrlevel index)
     * — max_pwrlevel caps the governor, default_pwrlevel deepens the idle
     * level. Restores happen through the profile handoff values.
     */
    private fun clampGpu(state: State) {
        val maxTarget = ThermalController.gpuClampTarget(readInt(GPU_MAX_PWRLEVEL), state.gpuMaxPwrLevel)
        if (maxTarget != null) writeNode(GPU_MAX_PWRLEVEL, maxTarget.toString(), raise = true)
        val defaultTarget = ThermalController.gpuClampTarget(readInt(GPU_DEFAULT_PWRLEVEL), state.gpuDefaultPwrLevel)
        if (defaultTarget != null) writeNode(GPU_DEFAULT_PWRLEVEL, defaultTarget.toString(), raise = true)
    }

    /** Writes the active profile's CPU max (may raise) and GPU levels back. */
    private fun restoreProfileLimits() {
        val limits = ThermalController.parseProfileLimits(readProfileMax())
        limits.policy0Max?.let {
            writeNode("/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq", it.toString(), raise = true)
        }
        limits.policy6Max?.let {
            writeNode("/sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq", it.toString(), raise = true)
        }
        limits.gpuMaxPwrLevel?.let { writeNode(GPU_MAX_PWRLEVEL, it.toString(), raise = true) }
        limits.gpuDefaultPwrLevel?.let { writeNode(GPU_DEFAULT_PWRLEVEL, it.toString(), raise = true) }
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

    /**
     * Records the outcome of one clamp episode (AZenith thermalcore's
     * effectiveness idea): start temperature vs the temperature when the
     * clamp was lifted. Diagnostics surfaces it; no policy depends on it.
     */
    private fun recordEffectiveness(startDeci: Int, endDeci: Int) {
        val gain = ThermalController.thermalGainC(startDeci, endDeci)
        prefs().edit()
            .putFloat(KEY_LAST_GAIN, gain.toFloat())
            .putLong(KEY_LAST_AT, System.currentTimeMillis())
            .apply()
        ShellLog.log(
            "ThermalService",
            "clamp episode ended: ${startDeci / 10}C -> ${endDeci / 10}C (gain ${"%.1f".format(gain)}C)"
        )
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
        private const val KEY_LAST_GAIN = "last_clamp_gain_c"
        private const val KEY_LAST_AT = "last_clamp_at"
        private val POLICIES = intArrayOf(0, 6)
        private val GPU_MAX_PWRLEVEL = "${ShellNodes.GPU}/max_pwrlevel"
        private val GPU_DEFAULT_PWRLEVEL = "${ShellNodes.GPU}/default_pwrlevel"

        @Volatile
        var isRunning: Boolean = false
            private set

        /** Diagnostics: last clamp-episode result, or null when none yet. */
        fun lastEffectiveness(context: Context): String? {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!p.contains(KEY_LAST_GAIN)) return null
            val gain = p.getFloat(KEY_LAST_GAIN, 0f)
            val at = p.getLong(KEY_LAST_AT, 0L)
            val mins = if (at > 0) (System.currentTimeMillis() - at) / 60_000 else 0
            return String.format(java.util.Locale.US, "clamp ΔT=%.1fC, %d min ago", gain, mins)
        }

        /** Starts the guard if it isn't already alive. False → caller falls back. */
        fun start(context: Context): Boolean {
            if (isRunning) return true
            // TRUE OFF: the thermal guard is an actuator (writes scaling_max).
            if (!TrueOff.allowsWrite(context)) return false
            // Monitor mode: writes cannot land — do not pretend to guard.
            if (!CheckRootStatus.isAvailable()) return false
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
            // Always attempt the stop: the service may outlive a process
            // restart where [isRunning] was reset to false.
            try {
                context.stopService(Intent(context, ThermalService::class.java))
            } catch (ex: Exception) {
                ShellLog.log("ThermalService.stop", ex.message ?: "error", error = true)
            }
            isRunning = false
        }
    }
}
