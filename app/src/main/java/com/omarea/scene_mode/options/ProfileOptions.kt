package com.omarea.scene_mode.options

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.omarea.common.shared.FileWrite
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.ShellEscape
import com.omarea.store.SpfConfig
import com.omarea.utils.DisplayModes
import com.omarea.utils.GovernorCapabilities
import com.omarea.utils.MiuiBoosterHints
import com.omarea.utils.QtiPerfHints
import com.omarea.utils.SceneLog
import com.omarea.scene_mode.game.GameListStore
import com.omarea.scene_mode.game.GameProfileStore
import com.omarea.scene_mode.game.MiuGameInfo
import com.omarea.scene_mode.monitor.SceneStatus
import com.omarea.scene_mode.power.BypassCharge
import com.omarea.scene_mode.game.GamePreloader
import com.omarea.scene_mode.ModeSwitcher

/**
 * Tuning layer applied on top of the powercfg profile scripts.
 *
 * The platform scripts own the bulk of the per-mode tuning; this layer adds the
 * options that are device-independent and cheap to re-apply: a percentage
 * frequency limiter, lite mode, governor / I/O scheduler preference, game
 * process priority, DND handling, game preload and optional system tweaks.
 *
 * Concept adapted from AZenith (Apache-2.0), reimplemented for a module-less
 * root environment and Scene's own mode engine.
 */
object ProfileOptions {
    @Volatile
    private var scriptPath: String? = null

    @Volatile
    private var boostScriptPath: String? = null

    @Volatile
    private var tuneLibPath: String? = null

    /** True while a game package is in the foreground. */
    @Volatile
    var gameActive: Boolean = false
        private set

    /** Package of the game the options were last applied for ("" when none). */
    @Volatile
    var gamePackage: String = ""
        private set

    /** Battery temperature that last triggered the thermal guard (0 = off). */
    @Volatile
    var thermalGuardActive: Boolean = false
        private set

    /**
     * True while the Off mode reset is in effect: the watchdog's re-apply must
     * never re-install option state while "no profile" is selected.
     */
    @Volatile
    private var offResetApplied = false

    /**
     * The package the options were last applied for. [reapply] re-uses it so a
     * periodic re-apply keeps the game session (DND, bypass, renderer, status)
     * intact instead of running the non-game path with an empty package.
     */
    @Volatile
    private var lastPackage: String = ""

    data class Config(
        val enabled: Boolean,
        val limitPercent: Int,
        val gpuLimitPercent: Int,
        val lightCpuLimit: Int,
        val lightGpuLimit: Int,
        val lightDetect: Boolean,
        val liteMode: Boolean,
        val governor: String,
        val ioScheduler: String,
        /** Adreno devfreq governor (Custom profile only). */
        val gpuGovernor: String,
        val pidPriority: Boolean,
        val dndOnGame: Boolean,
        val gamePreload: Boolean,
        val preloadBudgetMb: Int,
        val bypassChargeInGame: Boolean,
        val extraTweaks: Boolean,
        val gameDownscale: Int,
        val gameTargetFps: Int,
        val gameRenderer: String,
        /** Per-game display mode id applied while the game runs (0 = untouched). */
        val gameRefreshRate: Int,
        val dropCachesOnGame: Boolean,
        val gameDdrFloor: Boolean,
        /** Experimental: QTI perf-HAL game boost hint on game start. */
        val qtiHints: Boolean,
        /** MIUI thermal mode forced while a game runs (0 = leave MIUI alone). */
        val miuiThermalMode: Int,
        /** Raise the cpu_boost input window while a game runs. */
        val cpuBoost: Boolean,
        /** Use MIUI's own per-game target FPS as the default refresh rate. */
        val miuiRefreshDefault: Boolean,
        /** Relax the platform's idle boosts while nothing interactive runs. */
        val batteryEco: Boolean,
        /** Start the stock msm_irqbalance service (shipped disabled by MIUI). */
        val irqBalance: Boolean,
        val qualcommBus: Boolean,
        val qualcommGpu: Boolean,
        val qualcommGpuPowersave: Boolean,
        val govTunes: Boolean,
        val stopTrace: Boolean,
        val stopLoggers: Boolean,
        val globalRenderer: String,
        /** Keep MIUI's sys.sptm.gover in sync and patch the big cluster. */
        val sptmGover: Boolean,
        /** Raise the little-cluster colocation floor on performance profiles. */
        val colocBoost: Boolean,
        /** Disable UFS clock gating / Hibern8 while a game runs. */
        val ufsIdleBoost: Boolean,
        /** Use MIUI's own booster service (MiuiBooster.jar) during a game. */
        val miuiBooster: Boolean,
        /** Send the unlimited Qualcomm drag hint (0x1087) during a game. */
        val qtiDragBoost: Boolean,
        val disabled: Boolean = false
    )

    fun load(context: Context): Config {
        val spf = context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
        return Config(
            enabled = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_OPTIONS, true),
            limitPercent = spf.getInt(SpfConfig.GLOBAL_SPF_PROFILE_LIMIT_PERCENT, 0),
            gpuLimitPercent = spf.getInt(SpfConfig.GLOBAL_SPF_PROFILE_GPU_LIMIT, 0),
            lightCpuLimit = spf.getInt(
                SpfConfig.GLOBAL_SPF_PROFILE_LIGHT_CPU_LIMIT,
                SpfConfig.GLOBAL_SPF_PROFILE_LIGHT_CPU_LIMIT_DEFAULT
            ),
            lightGpuLimit = spf.getInt(
                SpfConfig.GLOBAL_SPF_PROFILE_LIGHT_GPU_LIMIT,
                SpfConfig.GLOBAL_SPF_PROFILE_LIGHT_GPU_LIMIT_DEFAULT
            ),
            lightDetect = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_LIGHT_DETECT, true),
            liteMode = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_LITE, false),
            governor = spf.getString(SpfConfig.GLOBAL_SPF_PROFILE_GOVERNOR, "") ?: "",
            ioScheduler = spf.getString(SpfConfig.GLOBAL_SPF_PROFILE_IOSCHED, "") ?: "",
            gpuGovernor = spf.getString(SpfConfig.GLOBAL_SPF_PROFILE_GPU_GOVERNOR, "") ?: "",
            pidPriority = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_PID_PRIORITY, true),
            dndOnGame = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_DND_GAME, false),
            gamePreload = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_PRELOAD, false),
            preloadBudgetMb = spf.getInt(SpfConfig.GLOBAL_SPF_PROFILE_PRELOAD_BUDGET, 500),
            bypassChargeInGame = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_BYPASS_GAME, false),
            extraTweaks = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_EXTRA_TWEAKS, false),
            gameDownscale = spf.getInt(SpfConfig.GLOBAL_SPF_PROFILE_GAME_DOWNSCALE, 0),
            gameTargetFps = spf.getInt(SpfConfig.GLOBAL_SPF_PROFILE_GAME_FPS, 0),
            gameRenderer = spf.getString(SpfConfig.GLOBAL_SPF_PROFILE_GAME_RENDERER, "") ?: "",
            gameRefreshRate = 0,
            dropCachesOnGame = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_DROP_CACHES, false),
            gameDdrFloor = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_GAME_DDR_FLOOR, true),
            qtiHints = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_QTI_HINTS, false),
            miuiThermalMode = spf.getInt(SpfConfig.GLOBAL_SPF_PROFILE_MIUI_THERMAL, 0),
            cpuBoost = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_CPU_BOOST, false),
            miuiRefreshDefault = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_MIUI_REFRESH, true),
            batteryEco = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_BATTERY_ECO, true),
            irqBalance = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_IRQ_BALANCE, false),
            qualcommBus = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_QCOM_BUS, false),
            qualcommGpu = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_QCOM_GPU, false),
            qualcommGpuPowersave = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_QCOM_GPU_PS, false),
            govTunes = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_GOV_TUNES, false),
            stopTrace = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_STOP_TRACE, false),
            stopLoggers = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_STOP_LOGGERS, false),
            globalRenderer = spf.getString(SpfConfig.GLOBAL_SPF_PROFILE_GLOBAL_RENDERER, "") ?: "",
            sptmGover = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_SPTM_GOVER, true),
            colocBoost = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_COLOC_BOOST, false),
            ufsIdleBoost = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_UFS_IDLE_BOOST, true),
            miuiBooster = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_MIUI_BOOSTER, false),
            qtiDragBoost = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_QTI_DRAG, false)
        )
    }

    /** Global config merged with the per-app overrides of [packageName]. */
    fun loadForApp(context: Context, packageName: String, base: Config = load(context)): Config {
        if (packageName.isEmpty()) {
            return base
        }
        val override = AppOptionsStore.load(context, packageName)
        if (override.isEmpty()) {
            return base
        }
        return base.copy(
            disabled = override.enabled == 0,
            liteMode = if (override.lite != AppOptionsStore.FOLLOW) override.lite == 1 else base.liteMode,
            gamePreload = if (override.preload != AppOptionsStore.FOLLOW) override.preload == 1 else base.gamePreload,
            dndOnGame = if (override.dnd != AppOptionsStore.FOLLOW) override.dnd == 1 else base.dndOnGame,
            bypassChargeInGame = if (override.bypass != AppOptionsStore.FOLLOW) override.bypass == 1 else base.bypassChargeInGame,
            gameDownscale = if (override.downscale != AppOptionsStore.FOLLOW) override.downscale else base.gameDownscale,
            gameTargetFps = if (override.fps != AppOptionsStore.FOLLOW) override.fps else base.gameTargetFps,
            gameRenderer = if (override.renderer != AppOptionsStore.RENDERER_FOLLOW) {
                if (override.renderer == AppOptionsStore.RENDERER_OFF) "" else override.renderer
            } else {
                base.gameRenderer
            },
            gameRefreshRate = if (override.refresh != AppOptionsStore.FOLLOW && override.refresh > 0) {
                override.refresh
            } else {
                base.gameRefreshRate
            },
            cpuBoost = if (override.cpuBoost != AppOptionsStore.FOLLOW) {
                override.cpuBoost == 1
            } else {
                base.cpuBoost
            },
            miuiThermalMode = if (override.miuiThermal != AppOptionsStore.FOLLOW) {
                override.miuiThermal
            } else {
                base.miuiThermalMode
            },
            miuiRefreshDefault = if (override.miuiRefresh != AppOptionsStore.FOLLOW) {
                override.miuiRefresh == 1
            } else {
                base.miuiRefreshDefault
            }
        )
    }

    private fun ensureTuneLib(context: Context): String? {
        tuneLibPath?.let { return it }
        return try {
            FileWrite.writePrivateShellFile(
                "addin/scene_tune_lib.sh",
                "addin/scene_tune_lib.sh",
                context
            ).also { tuneLibPath = it }
        } catch (ex: Exception) {
            SceneLog.e("ProfileOptions", "failed to extract tune lib", ex)
            null
        }
    }

    private fun ensureScript(context: Context): String? {
        scriptPath?.let { return it }
        // Both option scripts source the shared lib from their own directory.
        ensureTuneLib(context) ?: return null
        return try {
            FileWrite.writePrivateShellFile(
                "addin/scene_profile_options.sh",
                "addin/scene_profile_options.sh",
                context
            ).also { scriptPath = it }
        } catch (ex: Exception) {
            SceneLog.e("ProfileOptions", "failed to extract applier script", ex)
            null
        }
    }

    private fun ensureBoostScript(context: Context): String? {
        boostScriptPath?.let { return it }
        ensureTuneLib(context) ?: return null
        return try {
            FileWrite.writePrivateShellFile(
                "addin/scene_qualcomm_boost.sh",
                "addin/scene_qualcomm_boost.sh",
                context
            ).also { boostScriptPath = it }
        } catch (ex: Exception) {
            SceneLog.e("ProfileOptions", "failed to extract boost script", ex)
            null
        }
    }

    /** Whether the package is a game: the user list first, then the app category. */
    fun isGame(context: Context, packageName: String?): Boolean {
        if (packageName.isNullOrEmpty()) {
            return false
        }
        if (GameListStore.isGame(context, packageName)) {
            return true
        }
        return try {
            context.packageManager.getApplicationInfo(packageName, 0)
                .category == ApplicationInfo.CATEGORY_GAME
        } catch (ex: Exception) {
            false
        }
    }

    /**
     * Apply the options for the given mode and foreground app.
     * Safe to call on mode switches, app switches and screen-on events.
     */
    fun apply(
        context: Context,
        mode: String,
        packageName: String = "",
        config: Config = load(context)
    ) {
        if (mode == ModeSwitcher.OFF) {
            // Off keeps the layer silent; reset once per entry so a watchdog
            // tick does not re-run the reset scripts every minute.
            if (!offResetApplied) {
                offResetApplied = true
                reset(context)
            }
            return
        }
        offResetApplied = false
        // Keep the powercfg scripts' governor chains in sync with what this
        // kernel actually advertises (no-op when nothing changed).
        GovernorCapabilities.syncChains()
        // The global renderer is a standing override, applied even while the
        // rest of the options layer is switched off, then reverted on reset.
        if (!config.enabled) {
            // Another writer (BootGuard) can switch the master off without
            // going through the dialog's reset, so every disabled apply
            // re-undoes the layer's persistent side effects instead of
            // assuming a one-time reset happened. Everything here is
            // idempotent and prop-based, so it stays cheap per tick.
            gameActive = false
            gamePackage = ""
            setGlobalRenderer("")
            restoreGameRenderer()
            restoreGameRefresh(context)
            releaseGameBoosts()
            if (thermalGuardActive) {
                releaseThermalGuard(context)
            }
            if (BypassCharge.isAuto()) {
                BypassCharge.setReason(BypassCharge.REASON_GAME, false)
            }
            updateDnd(context, false, config)
            SceneStatus.write(mode, packageName, isGame(context, packageName))
            return
        }
        setGlobalRenderer(config.globalRenderer)
        val effective = loadForApp(context, packageName, config)
        val script = ensureScript(context) ?: return
        val boostScript = ensureBoostScript(context)
        val game = isGame(context, packageName)
        lastPackage = packageName

        if (effective.disabled) {
            // The user opted this app out of the options layer entirely: undo
            // what it applied and let the platform profile run alone.
            resetScripts(script, boostScript)
            gameActive = false
            gamePackage = ""
            thermalGuardActive = false
            updateDnd(context, false, effective)
            if (BypassCharge.isAuto()) {
                BypassCharge.setReason(BypassCharge.REASON_GAME, false)
            }
            releaseGameBoosts()
            restoreGameRenderer()
            restoreGameRefresh(context)
            SceneStatus.write(mode, packageName, game)
            return
        }

        val env = StringBuilder()
        // A game classified as light gets the light-game caps, which ride
        // their own layer so they never disturb the global limiter or the
        // profile's caps. Balance is included because it is the fallback
        // profile on providers without `light`.
        val lightGame = game &&
            GameProfileStore.classOf(packageName) == GameProfileStore.CLASS_LIGHT &&
            (mode == ModeSwitcher.FAST || mode == ModeSwitcher.LIGHT || mode == ModeSwitcher.BALANCE)
        env.append("export SCENE_MODE=").append(ShellEscape.quote(mode)).append("\n")
        env.append("export SCENE_LIMIT_PERCENT=").append(ShellEscape.quote(config.limitPercent.toString())).append("\n")
        env.append("export SCENE_GPU_LIMIT=").append(ShellEscape.quote(config.gpuLimitPercent.toString())).append("\n")
        env.append("export SCENE_LIGHT_CPU=")
            .append(ShellEscape.quote(if (lightGame) config.lightCpuLimit.toString() else "0")).append("\n")
        env.append("export SCENE_LIGHT_GPU=")
            .append(ShellEscape.quote(if (lightGame) config.lightGpuLimit.toString() else "0")).append("\n")
        env.append("export SCENE_LITE=").append(ShellEscape.quote(if (effective.liteMode) "1" else "0")).append("\n")
        // The Custom profile owns the user's CPU/GPU/IO governor choices; the
        // three main profiles get their scenario governors from the powercfg
        // layer (powersave/balance: schedutil with the endurance or daily
        // tuning, performance: the performance governor), so the preferences
        // are sent as empty strings everywhere else and the script restores.
        val custom = mode == ModeSwitcher.FAST
        env.append("export SCENE_GOVERNOR=").append(ShellEscape.quote(if (custom) config.governor else "")).append("\n")
        env.append("export SCENE_IOSCHED=").append(ShellEscape.quote(if (custom) config.ioScheduler else "")).append("\n")
        env.append("export SCENE_GPU_GOVERNOR=").append(ShellEscape.quote(if (custom) config.gpuGovernor else "")).append("\n")
        env.append("export SCENE_PID=").append(ShellEscape.quote(if (config.pidPriority) "1" else "0")).append("\n")
        env.append("export SCENE_GAME_PKG=").append(ShellEscape.quote(if (game) packageName else "")).append("\n")
        env.append("export SCENE_GAME_DDR_FLOOR=")
            .append(ShellEscape.quote(if (game && config.gameDdrFloor && !lightGame) "1" else "0"))
            .append("\n")
        // MIUI-specific game tuning: the kernel cpu_boost input window and the
        // mi_thermald mode (the script validates the config exists and restores
        // the previous mode when the game leaves).
        env.append("export SCENE_CPU_BOOST=")
            .append(ShellEscape.quote(if (game && effective.cpuBoost) "1" else "0")).append("\n")
        env.append("export SCENE_MIUI_THERMAL_MODE=")
            .append(ShellEscape.quote(if (game) effective.miuiThermalMode.toString() else "0")).append("\n")
        // Battery efficiency: relax the platform's idle boosts while nothing
        // interactive runs (frugal profiles only).
        val batteryEco = !game && effective.batteryEco &&
            (mode == ModeSwitcher.POWERSAVE || mode == ModeSwitcher.BALANCE)
        env.append("export SCENE_BATTERY_ECO=")
            .append(ShellEscape.quote(if (batteryEco) "1" else "0")).append("\n")
        // Stock MIUI IRQ balancer (the ROM ships the binary/conf but keeps the
        // service disabled): opt-in, started and stopped by the script.
        env.append("export SCENE_IRQBAL=")
            .append(ShellEscape.quote(if (config.irqBalance) "1" else "0")).append("\n")
        env.append("export SCENE_EXTRA_TWEAKS=").append(ShellEscape.quote(if (config.extraTweaks) "1" else "0")).append("\n")
        env.append("export SCENE_GAME_DOWNSCALE=").append(ShellEscape.quote(effective.gameDownscale.toString())).append("\n")
        env.append("export SCENE_GAME_FPS=").append(ShellEscape.quote(effective.gameTargetFps.toString())).append("\n")
        env.append("export SCENE_DROP_CACHES=").append(ShellEscape.quote(if (effective.dropCachesOnGame) "1" else "0")).append("\n")
        env.append("export SCENE_QCOM_BUS=").append(ShellEscape.quote(if (config.qualcommBus) "1" else "0")).append("\n")
        env.append("export SCENE_QCOM_GPU=").append(ShellEscape.quote(if (config.qualcommGpu) "1" else "0")).append("\n")
        env.append("export SCENE_QCOM_GPU_PS=").append(ShellEscape.quote(if (config.qualcommGpuPowersave) "1" else "0")).append("\n")
        env.append("export SCENE_GOV_TUNES=").append(ShellEscape.quote(if (config.govTunes) "1" else "0")).append("\n")
        env.append("export SCENE_STOP_TRACE=").append(ShellEscape.quote(if (config.stopTrace) "1" else "0")).append("\n")
        env.append("export SCENE_STOP_LOGGERS=").append(ShellEscape.quote(if (config.stopLoggers) "1" else "0")).append("\n")
        // MIUI platform switches. SPTM is on by default because it only keeps the
        // framework's own property truthful and patches a cluster the platform
        // rule misses; the other two are opt-in.
        env.append("export SCENE_SPTM_GOVER=").append(ShellEscape.quote(if (config.sptmGover) "1" else "0")).append("\n")
        env.append("export SCENE_COLOC_FMIN=")
            .append(
                ShellEscape.quote(
                    if (config.colocBoost) SpfConfig.GLOBAL_SPF_PROFILE_COLOC_FMIN_KHZ.toString() else "0"
                )
            )
            .append("\n")
        env.append("export SCENE_UFS_IDLE_BOOST=").append(ShellEscape.quote(if (config.ufsIdleBoost) "1" else "0")).append("\n")
        env.append("export SCENE_SDK=").append(Build.VERSION.SDK_INT).append("\n")
        if (!game && (effective.gameDownscale > 0 || effective.gameTargetFps > 0)) {
            env.append("export SCENE_GAME_RESET=1\n")
        }
        env.append("sh ").append(ShellEscape.quote(script)).append(" > /dev/null 2>&1")
        if (boostScript != null) {
            env.append("\nsh ").append(ShellEscape.quote(boostScript)).append(" > /dev/null 2>&1")
        }

        KeepShellPublic.doCmdSync(env.toString())

        gameActive = game
        gamePackage = if (game) packageName else ""
        SceneStatus.write(mode, packageName, game)
        updateDnd(context, game, effective)

        if (game) {
            if (effective.bypassChargeInGame) {
                BypassCharge.enableIfNeeded(context, auto = true)
            }
            if (effective.gamePreload) {
                GamePreloader.preload(context, packageName, config.preloadBudgetMb)
            }
            if (effective.gameRenderer.isNotEmpty()) {
                applyGameRenderer(packageName, effective.gameRenderer)
            }
            if (config.qtiHints) {
                // Vendor game boost through the QTI perf HAL, when reachable.
                QtiPerfHints.gameBoost(packageName)
            }
            if (config.miuiBooster) {
                applyMiuiBooster(context)
            }
            if (config.qtiDragBoost && QtiPerfHints.dragBoost(packageName)) {
                // 0x1087 has Timeout=0 in the vendor config: the HAL never
                // expires it, so record that a release is owed.
                KeepShellPublic.doCmdSync("setprop $PROP_DRAG_HELD 1")
            }
            applyGameRefresh(context, gameRefreshTarget(context, effective, packageName))
        } else {
            if (effective.bypassChargeInGame && BypassCharge.isAuto()) {
                // Only release the auto path; a manual toggle or the charge
                // protection level keeps their own reason.
                BypassCharge.setReason(BypassCharge.REASON_GAME, false)
            }
            releaseGameBoosts()
            restoreGameRenderer()
            restoreGameRefresh(context)
        }
    }

    /**
     * Re-apply after the screen turns on, because vendors often reset caps.
     * Runs for the package the last real apply targeted, so a watchdog tick
     * during a game does not tear the session down.
     */
    fun reapply(context: Context) {
        val config = load(context)
        if (!config.enabled) {
            return
        }
        val mode = ModeSwitcher.getCurrentPowerMode()
        if (mode.isEmpty()) {
            return
        }
        apply(context, mode, lastPackage, config)
    }

    /**
     * Undo everything the options layer may have applied — the applier state
     * (limiter, boost, guard), the renderer slots, the DND override and the
     * game bypass reason — so "options off" really means "layer silent".
     */
    fun reset(context: Context) {
        val script = ensureScript(context) ?: return
        resetScripts(script, ensureBoostScript(context))
        gameActive = false
        gamePackage = ""
        thermalGuardActive = false
        lastPackage = ""
        setGlobalRenderer("")
        restoreGameRenderer()
        restoreGameRefresh(context)
        releaseGameBoosts()
        if (BypassCharge.isAuto()) {
            BypassCharge.setReason(BypassCharge.REASON_GAME, false)
        }
        updateDnd(context, false, load(context))
        SceneStatus.write(ModeSwitcher.getCurrentPowerMode(), "", false)
    }

    /**
     * Thermal guard layer: caps CPU/GPU while the battery runs hot during a
     * game. The active state lives in props so a mode switch re-applies the
     * guard from the options script, and the caps are restored to whatever the
     * user limiter or kernel had when the guard releases.
     */
    fun setThermalGuard(context: Context, active: Boolean, percent: Int) {
        if (!active && !thermalGuardActive) {
            return
        }
        if (!active) {
            releaseThermalGuard(context)
            return
        }
        val script = ensureScript(context) ?: return
        thermalGuardActive = true
        KeepShellPublic.doCmdSync(
            "setprop vtools.scene.guard.percent " + ShellEscape.quote(percent.toString()) + "\n" +
                "setprop vtools.scene.guard.active 1\n" +
                "export SCENE_GUARD_ONLY=1\nexport SCENE_GUARD=1\nexport SCENE_GUARD_PERCENT=" +
                ShellEscape.quote(percent.toString()) + "\n" +
                "sh " + ShellEscape.quote(script) + " > /dev/null 2>&1"
        )
        SceneLog.i("ProfileOptions", "thermal guard on (cap $percent%)")
    }

    /** Force the guard layer off, even when this process did not enable it. */
    fun releaseThermalGuard(context: Context) {
        thermalGuardActive = false
        val script = ensureScript(context) ?: return
        KeepShellPublic.doCmdSync(
            "setprop vtools.scene.guard.active 0\n" +
                "export SCENE_GUARD_ONLY=1\nexport SCENE_GUARD=0\n" +
                "sh " + ShellEscape.quote(script) + " > /dev/null 2>&1"
        )
        SceneLog.i("ProfileOptions", "thermal guard off")
    }

    /** Run the options applier in reset mode and release the boost nodes. */
    private fun resetScripts(script: String, boostScript: String?) {
        val cmd = StringBuilder()
        cmd.append("export SCENE_QCOM_BUS=0\nexport SCENE_QCOM_GPU=0\nexport SCENE_QCOM_GPU_PS=0\n")
        cmd.append("export SCENE_RESET=1\n")
        cmd.append("sh ").append(ShellEscape.quote(script)).append(" > /dev/null 2>&1")
        if (boostScript != null) {
            cmd.append("\nsh ").append(ShellEscape.quote(boostScript)).append(" > /dev/null 2>&1")
        }
        KeepShellPublic.doCmdSync(cmd.toString())
    }

    // +---------------------------------------------------------------+
    // | HWUI renderer overrides (global + per-game, shared mechanism)  |
    // +---------------------------------------------------------------+

    private const val PROP_RENDERER_BACKUP = "vtools.scene.renderer.bak"
    private const val PROP_RENDERER_SET = "vtools.scene.renderer.set"
    private const val PROP_GLOBAL_RENDERER_BACKUP = "vtools.scene.renderer.gbak"
    private const val PROP_GLOBAL_RENDERER_SET = "vtools.scene.renderer.gset"

    /**
     * Set or clear one renderer override slot. Every slot remembers the
     * original value together with an explicit "set" flag, because the stock
     * value is often the empty string and an empty backup alone cannot tell
     * "never touched" from "was empty".
     *
     * Returns true when the property value actually changed.
     */
    private fun setRenderer(target: String, backupProp: String, setProp: String): Boolean {
        val set = KeepShellPublic.doCmdSync("getprop $setProp").trim() == "1"
        val current = KeepShellPublic.doCmdSync("getprop debug.hwui.renderer").trim()
        if (target.isEmpty()) {
            if (!set) {
                return false
            }
            val backup = KeepShellPublic.doCmdSync("getprop $backupProp").trim()
            KeepShellPublic.doCmdSync(
                "setprop debug.hwui.renderer " + ShellEscape.quote(backup) + "\n" +
                    "setprop $backupProp \"\"\n" +
                    "setprop $setProp 0"
            )
            return true
        }
        val cmd = StringBuilder()
        if (!set) {
            cmd.append("setprop ").append(backupProp).append(" ").append(ShellEscape.quote(current)).append("\n")
                .append("setprop ").append(setProp).append(" 1\n")
        }
        if (current == target) {
            if (cmd.isNotEmpty()) {
                KeepShellPublic.doCmdSync(cmd.toString())
            }
            return false
        }
        cmd.append("setprop debug.hwui.renderer ").append(ShellEscape.quote(target))
        KeepShellPublic.doCmdSync(cmd.toString())
        return true
    }

    /**
     * Switch the HWUI renderer for a game. The property is only read when a
     * process starts, so the game is restarted when the value changes.
     */
    private fun applyGameRenderer(packageName: String, renderer: String) {
        if (!setRenderer(renderer, PROP_RENDERER_BACKUP, PROP_RENDERER_SET)) {
            return
        }
        KeepShellPublic.doCmdSync(
            "am force-stop " + ShellEscape.quote(packageName) + "\n" +
                "sleep 1\n" +
                "monkey -p " + ShellEscape.quote(packageName) + " -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1"
        )
        SceneLog.i("ProfileOptions", "game renderer $renderer applied to $packageName")
    }

    private fun restoreGameRenderer() {
        setRenderer("", PROP_RENDERER_BACKUP, PROP_RENDERER_SET)
    }

    // +---------------------------------------------------------------+
    // | Per-game refresh rate                                          |
    // +---------------------------------------------------------------+

    private const val PROP_REFRESH_BACKUP = "vtools.scene.refresh.bak"
    private const val PROP_REFRESH_SET = "vtools.scene.refresh.set"

    /**
     * The display mode a game should run at: the user's explicit per-game
     * override wins, otherwise MIUI's own Game Turbo target FPS is used (the
     * same value Joyose writes to the panel), so Scene and the ROM agree
     * instead of fighting over the refresh rate. 0 = leave the display alone.
     */
    private fun gameRefreshTarget(context: Context, effective: Config, packageName: String): Int {
        if (effective.gameRefreshRate > 0) {
            return effective.gameRefreshRate
        }
        if (!effective.miuiRefreshDefault || packageName.isEmpty()) {
            return 0
        }
        return try {
            val fps = MiuGameInfo.query()[packageName]?.fps?.toIntOrNull() ?: return 0
            if (fps <= 0) {
                return 0
            }
            DisplayModes.list(context).firstOrNull { it.hz == fps }?.id ?: 0
        } catch (ex: Exception) {
            SceneLog.e("ProfileOptions", "MIUI refresh lookup failed", ex)
            0
        }
    }

    /**
     * Switch the display mode while a game runs. The mode active on entry is
     * snapshotted in a prop (cleared on restore), so leaving the game returns to
     * whatever Android/MIUI had selected — including a manual pick.
     */
    private fun applyGameRefresh(context: Context, target: Int) {
        if (target <= 0) {
            restoreGameRefresh(context)
            return
        }
        try {
            val set = KeepShellPublic.doCmdSync("getprop $PROP_REFRESH_SET").trim() == "1"
            if (!set) {
                val current = DisplayModes.active(context) ?: return
                KeepShellPublic.doCmdSync(
                    "setprop $PROP_REFRESH_BACKUP " + current + "\n" +
                        "setprop $PROP_REFRESH_SET 1"
                )
                if (current != target) {
                    DisplayModes.set(context, target)
                }
                return
            }
            if (DisplayModes.active(context) != target) {
                DisplayModes.set(context, target)
            }
        } catch (ex: Exception) {
            SceneLog.e("ProfileOptions", "game refresh apply failed", ex)
        }
    }

    private fun restoreGameRefresh(context: Context) {
        try {
            if (KeepShellPublic.doCmdSync("getprop $PROP_REFRESH_SET").trim() != "1") {
                return
            }
            val backup = KeepShellPublic.doCmdSync("getprop $PROP_REFRESH_BACKUP").trim()
            val id = backup.toIntOrNull()
            if (id != null) {
                DisplayModes.set(context, id)
            }
            KeepShellPublic.doCmdSync(
                "setprop $PROP_REFRESH_SET 0\nsetprop $PROP_REFRESH_BACKUP \"\""
            )
        } catch (ex: Exception) {
            SceneLog.e("ProfileOptions", "game refresh restore failed", ex)
        }
    }

    /**
     * Standing renderer override for every app (AZenith global renderer),
     * sharing the same slot mechanism as the per-game renderer.
     */
    private fun setGlobalRenderer(target: String) {
        try {
            setRenderer(target, PROP_GLOBAL_RENDERER_BACKUP, PROP_GLOBAL_RENDERER_SET)
        } catch (ex: Exception) {
            SceneLog.e("ProfileOptions", "global renderer failed", ex)
        }
    }

    // +---------------------------------------------------------------+
    // | Vendor boosts that need a matching release                     |
    // +---------------------------------------------------------------+

    /** 15 s, the same window MIUI's own game boost uses. */
    private const val BOOST_TIMEOUT_MS = 15_000

    /** Set while the unlimited drag hint (0x1087) may still be held. */
    private const val PROP_DRAG_HELD = "vtools.scene.qti.drag"

    @Volatile
    private var boosterScriptPath: String? = null

    private fun ensureBoosterScript(context: Context): String? {
        boosterScriptPath?.let { return it }
        return try {
            FileWrite.writePrivateShellFile(
                "addin/miui_booster.sh",
                "addin/miui_booster.sh",
                context
            ).also { boosterScriptPath = it }
        } catch (ex: Exception) {
            SceneLog.e("ProfileOptions", "failed to extract booster script", ex)
            null
        }
    }

    /**
     * Ask MIUI's own booster service for CPU/GPU/DDR/IO headroom.
     *
     * The service is gated by a UID allow-list rather than a signature
     * permission, so this first adds Scene's UID to
     * `persist.sys.mibridge_auth_uids` (the script snapshots the original list
     * so `revoke` can put it back) and only then sends the requests.
     *
     * Every failure path is silent: the sysfs-based tuning in the shell layer is
     * the primary mechanism and does not depend on this.
     */
    private fun applyMiuiBooster(context: Context) {
        try {
            if (!MiuiBoosterHints.isAvailable(context)) {
                return
            }
            val uid = Process.myUid()
            if (!MiuiBoosterHints.isAuthorized()) {
                ensureBoosterScript(context)?.let { script ->
                    KeepShellPublic.doCmdSync(
                        "sh " + ShellEscape.quote(script) + " authorize " + uid + " > /dev/null 2>&1"
                    )
                }
                MiuiBoosterHints.invalidate()
                if (!MiuiBoosterHints.checkPermission(context, uid)) {
                    SceneLog.w("ProfileOptions", "MIUI booster refused uid $uid")
                    return
                }
            }
            MiuiBoosterHints.requestCpu(uid, MiuiBoosterHints.LEVEL_HIGH, BOOST_TIMEOUT_MS)
            MiuiBoosterHints.requestGpu(uid, MiuiBoosterHints.LEVEL_HIGH, BOOST_TIMEOUT_MS)
            MiuiBoosterHints.requestIo(uid, MiuiBoosterHints.LEVEL_HIGH, BOOST_TIMEOUT_MS)
            MiuiBoosterHints.requestMemory(uid, MiuiBoosterHints.LEVEL_MIDDLE, BOOST_TIMEOUT_MS)
        } catch (ex: Exception) {
            SceneLog.e("ProfileOptions", "MIUI booster request failed", ex)
        }
    }

    /**
     * Release everything a game session may have acquired.
     *
     * Both mechanisms here outlive their caller if left alone: the drag hint has
     * no timeout at all, and a booster request keeps its 15 s window running.
     * This runs on every non-game apply, not only on reset, so leaving a game
     * always clears them.
     */
    private fun releaseGameBoosts() {
        try {
            if (KeepShellPublic.doCmdSync("getprop $PROP_DRAG_HELD").trim() == "1") {
                if (QtiPerfHints.releaseDragBoost()) {
                    KeepShellPublic.doCmdSync("setprop $PROP_DRAG_HELD 0")
                }
            }
            if (MiuiBoosterHints.isAuthorized()) {
                MiuiBoosterHints.cancelAll(Process.myUid())
            }
        } catch (ex: Exception) {
            SceneLog.e("ProfileOptions", "release game boosts failed", ex)
        }
    }

    // +---------------------------------------------------------------+
    // | Do Not Disturb while gaming                                    |
    // +---------------------------------------------------------------+

    private const val DND_UNTOUCHED = -1

    private fun updateDnd(context: Context, gaming: Boolean, config: Config) {
        val spf = context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
        val backup = spf.getInt(SpfConfig.GLOBAL_SPF_DND_BACKUP, DND_UNTOUCHED)

        if (config.dndOnGame && gaming) {
            if (backup == DND_UNTOUCHED) {
                spf.edit().putInt(SpfConfig.GLOBAL_SPF_DND_BACKUP, readZenMode(context)).apply()
            }
            KeepShellPublic.doCmdSync("cmd notification set_dnd priority > /dev/null 2>&1")
        } else if (backup != DND_UNTOUCHED) {
            // `cmd notification set_dnd` only understands off/priority, so restore the exact
            // previous mode through the settings provider instead.
            KeepShellPublic.doCmdSync("settings put global zen_mode $backup > /dev/null 2>&1")
            spf.edit().putInt(SpfConfig.GLOBAL_SPF_DND_BACKUP, DND_UNTOUCHED).apply()
        }
    }

    private fun readZenMode(context: Context): Int {
        return try {
            Settings.Global.getInt(context.contentResolver, "zen_mode")
        } catch (ex: Exception) {
            0
        }
    }
}
