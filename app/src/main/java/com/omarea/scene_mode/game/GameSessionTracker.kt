package com.omarea.scene_mode.game

import android.content.Context
import com.omarea.common.shell.KeepShellPublic
import com.omarea.data.EventBus
import com.omarea.data.EventType
import com.omarea.data.GlobalStatus
import com.omarea.library.shell.FpsUtils
import com.omarea.library.shell.GpuUtils
import com.omarea.store.SpfConfig
import com.omarea.utils.MiuThermal
import com.omarea.utils.SceneLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.omarea.scene_mode.options.ProfileOptions
import com.omarea.scene_mode.ModeSwitcher

/**
 * One background ticker for the game-time features:
 *
 *  - records a session summary per game (battery level/drain, max temperature,
 *    average FPS, modes seen) into [GameSessionStore];
 *  - drives the thermal guard: while a game runs and the battery passes the
 *    configured temperature, CPU/GPU are capped through
 *    [ProfileOptions.setThermalGuard] and released once it cools down again.
 *
 * Started from the Application class; the loop samples only while a game is in
 * the foreground, so it costs nothing otherwise.
 */
object GameSessionTracker {
    private const val TICK_MS = 10_000L
    private const val GUARD_HYSTERESIS_C = 3.0

    /**
     * Xiaomi's own thermal state (`thermal_message/temp_state`) is a second
     * guard signal: 4 means mi_thermald is already in its hot state, 5 is the
     * emergency one. Acting on it lets the guard cap before the ROM starts
     * throttling hard, and keeps it active until MIUI itself cools down.
     */
    private const val MIUI_HOT_STATE = 4
    private const val MIUI_COOL_STATE = 2

    /** Frame-rate floor below which a "light" game is considered misclassified. */
    private const val LOW_FPS_FLOOR = 22.0
    private const val LOW_FPS_TICKS = 3

    @Volatile
    private var started = false

    @Volatile
    private var guardActive = false

    private var session: SessionBuilder? = null

    /** Consecutive low-frame ticks while a light-classified game is capped. */
    private var lowFpsTicks = 0

    private val fpsUtils by lazy { FpsUtils() }

    /** Application scoped; the ticker runs for the lifetime of the process. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class SessionBuilder(val packageName: String, val startedAt: Long) {
        var samples = 0
        var startLevel = -1
        var endLevel = -1
        var maxTempC = 0.0
        var maxTempState = 0
        var fpsSum = 0.0
        var fpsSamples = 0
        val modes = LinkedHashSet<String>()
        var guardActivations = 0
    }

    fun start(context: Context) {
        if (started) {
            return
        }
        started = true
        val appContext = context.applicationContext
        scope.launch {
            // Refresh the effective per-game profile map for the monitor.
            try {
                GameProfileStore.materialize(appContext)
            } catch (ex: Exception) {
                SceneLog.e("GameSessionTracker", "profile materialize failed", ex)
            }
            // A guard left active by a previous process must not survive.
            reconcileGuard(appContext)
            while (true) {
                try {
                    tick(appContext)
                } catch (ex: Exception) {
                    SceneLog.e("GameSessionTracker", "tick failed", ex)
                }
                delay(TICK_MS)
            }
        }
        SceneLog.i("GameSessionTracker", "started")
    }

    private fun reconcileGuard(context: Context) {
        try {
            val active = KeepShellPublic.doCmdSync("getprop vtools.scene.guard.active").trim() == "1"
            if (active) {
                ProfileOptions.releaseThermalGuard(context)
            }
        } catch (ex: Exception) {
            SceneLog.e("GameSessionTracker", "guard reconcile failed", ex)
        }
    }

    private fun tick(context: Context) {
        val gaming = ProfileOptions.gameActive
        val packageName = ProfileOptions.gamePackage
        if (gaming && packageName.isNotEmpty()) {
            val current = session
            if (current == null || current.packageName != packageName) {
                finalize(context)
                session = SessionBuilder(packageName, System.currentTimeMillis())
            }
            val fps = sample()
            maybeGuard(context)
            classify(context, packageName, fps)
        } else {
            finalize(context)
            releaseGuard(context)
        }
    }

    private fun sample(): Double {
        val builder = session ?: return 0.0
        val level = GlobalStatus.batteryCapacity
        if (builder.startLevel < 0) {
            builder.startLevel = level
        }
        builder.endLevel = level
        val temperature = GlobalStatus.updateBatteryTemperature()
        if (temperature > builder.maxTempC) {
            builder.maxTempC = temperature
        }
        val tempState = MiuThermal.tempState()
        if (tempState != null && tempState > builder.maxTempState) {
            builder.maxTempState = tempState
        }
        val fps = try {
            fpsUtils.fps.toDouble()
        } catch (ex: Exception) {
            0.0
        }
        if (fps > 1.0) {
            builder.fpsSum += fps
            builder.fpsSamples++
        }
        val mode = ModeSwitcher.getCurrentPowerMode()
        if (mode.isNotEmpty()) {
            builder.modes.add(mode)
        }
        builder.samples++
        return fps
    }

    /**
     * Light/heavy classification: feed the GPU busy percentage and the frame
     * rate into the shared profiler. A confirmed class is persisted and, when
     * the user did not pin a profile for the game, the resolved mode is applied
     * immediately so a light game stops burning the performance profile.
     */
    private fun classify(context: Context, packageName: String, fps: Double) {
        val config = ProfileOptions.load(context)
        if (!config.enabled || !config.lightDetect) {
            return
        }
        // Safety valve: a light-classified game that cannot hold frames is not
        // light (CPU-bound emulators, heavier scenes). Mark it heavy so the
        // light caps release and it is not downgraded again.
        val activeMode = ModeSwitcher.getCurrentPowerMode()
        val capped = activeMode == ModeSwitcher.FAST ||
            activeMode == ModeSwitcher.LIGHT ||
            activeMode == ModeSwitcher.BALANCE
        if (GameProfileStore.classOf(packageName) == GameProfileStore.CLASS_LIGHT && capped &&
            fps > 1.0 && fps < LOW_FPS_FLOOR
        ) {
            lowFpsTicks++
            if (lowFpsTicks >= LOW_FPS_TICKS) {
                lowFpsTicks = 0
                GameProfileStore.setClass(context, packageName, GameProfileStore.CLASS_HEAVY)
                SceneLog.i("GameSessionTracker", "$packageName could not hold frames, marked heavy")
                if (GameProfileStore.overrideFor(packageName) == null &&
                    ModeSwitcher.getCurrentPowerMode() != ModeSwitcher.PERFORMANCE
                ) {
                    ModeSwitcher().executePowercfgMode(ModeSwitcher.PERFORMANCE, packageName)
                    scope.launch(Dispatchers.Main) { EventBus.publish(EventType.SCENE_MODE_ACTION) }
                }
                return
            }
        } else {
            lowFpsTicks = 0
        }
        val decision = GameProfiler.observe(
            packageName,
            gpuBusyPercent(),
            if (fps > 1.0) fps else null
        ) ?: return
        if (decision == GameProfileStore.classOf(packageName)) {
            return
        }
        GameProfileStore.setClass(context, packageName, decision)
        SceneLog.i("GameSessionTracker", "$packageName classified as $decision")
        if (GameProfileStore.overrideFor(packageName) != null) {
            return
        }
        val mode = GameProfileStore.modeFor(context, packageName)
        if (mode != GameProfileStore.KEEP && mode.isNotEmpty() &&
            ModeSwitcher.getCurrentPowerMode() != mode
        ) {
            ModeSwitcher().executePowercfgMode(mode, packageName)
            // Refresh the status notification with the new mode (main thread:
            // the notification receiver is synchronous).
            scope.launch(Dispatchers.Main) { EventBus.publish(EventType.SCENE_MODE_ACTION) }
        }
    }

    /** GPU busy percentage from kgsl, or null when the kernel does not expose one. */
    private fun gpuBusyPercent(): Double? {
        return try {
            val load = GpuUtils.getGpuLoad()
            if (load in 0..100) load.toDouble() else null
        } catch (ex: Exception) {
            null
        }
    }

    private fun maybeGuard(context: Context) {
        // The dialog or a reset may have released the guard without this
        // ticker noticing; the Kotlin flag is the process-wide source of truth.
        if (!ProfileOptions.thermalGuardActive) {
            guardActive = false
        }
        val spf = context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
        if (!spf.getBoolean(SpfConfig.GLOBAL_SPF_THERMAL_GUARD, false)) {
            releaseGuard(context)
            return
        }
        val threshold = spf.getInt(
            SpfConfig.GLOBAL_SPF_THERMAL_GUARD_TEMP,
            SpfConfig.GLOBAL_SPF_THERMAL_GUARD_TEMP_DEFAULT
        ).toDouble()
        val percent = spf.getInt(
            SpfConfig.GLOBAL_SPF_THERMAL_GUARD_PERCENT,
            SpfConfig.GLOBAL_SPF_THERMAL_GUARD_PERCENT_DEFAULT
        )
        val temperature = GlobalStatus.updateBatteryTemperature()
        if (temperature <= 0) {
            return
        }
        val miuiState = MiuThermal.tempState() ?: 0
        if (!guardActive && (temperature >= threshold || miuiState >= MIUI_HOT_STATE)) {
            guardActive = true
            session?.guardActivations = (session?.guardActivations ?: 0) + 1
            ProfileOptions.setThermalGuard(context, true, percent)
        } else if (guardActive && temperature <= threshold - GUARD_HYSTERESIS_C &&
            miuiState <= MIUI_COOL_STATE
        ) {
            guardActive = false
            ProfileOptions.setThermalGuard(context, false, percent)
        }
    }

    private fun releaseGuard(context: Context) {
        if (!guardActive) {
            return
        }
        guardActive = false
        ProfileOptions.setThermalGuard(context, false, 0)
    }

    private fun finalize(context: Context) {
        val builder = session ?: return
        session = null
        if (builder.samples <= 0) {
            return
        }
        val spf = context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
        if (!spf.getBoolean(SpfConfig.GLOBAL_SPF_GAME_SESSIONS, true)) {
            return
        }
        GameSessionStore.append(
            context,
            GameSessionStore.GameSession(
                packageName = builder.packageName,
                startedAt = builder.startedAt,
                endedAt = System.currentTimeMillis(),
                samples = builder.samples,
                startLevel = builder.startLevel,
                endLevel = builder.endLevel,
                maxTempC = builder.maxTempC,
                maxTempState = builder.maxTempState,
                avgFps = if (builder.fpsSamples > 0) builder.fpsSum / builder.fpsSamples else 0.0,
                modes = builder.modes.joinToString(", "),
                guardActivations = builder.guardActivations
            )
        )
    }
}
