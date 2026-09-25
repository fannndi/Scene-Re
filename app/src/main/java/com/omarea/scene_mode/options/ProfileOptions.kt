package com.omarea.scene_mode.options

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.provider.Settings
import com.omarea.common.shared.FileWrite
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.ShellEscape
import com.omarea.store.SpfConfig
import com.omarea.utils.SceneLog
import com.omarea.scene_mode.game.GameListStore
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
        val liteMode: Boolean,
        val governor: String,
        val ioScheduler: String,
        val pidPriority: Boolean,
        val dndOnGame: Boolean,
        val gamePreload: Boolean,
        val preloadBudgetMb: Int,
        val bypassChargeInGame: Boolean,
        val extraTweaks: Boolean,
        val gameDownscale: Int,
        val gameTargetFps: Int,
        val gameRenderer: String,
        val dropCachesOnGame: Boolean,
        val qualcommBus: Boolean,
        val qualcommGpu: Boolean,
        val qualcommGpuPowersave: Boolean,
        val govTunes: Boolean,
        val stopTrace: Boolean,
        val stopLoggers: Boolean,
        val globalRenderer: String,
        val disabled: Boolean = false
    )

    fun load(context: Context): Config {
        val spf = context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
        return Config(
            enabled = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_OPTIONS, true),
            limitPercent = spf.getInt(SpfConfig.GLOBAL_SPF_PROFILE_LIMIT_PERCENT, 0),
            gpuLimitPercent = spf.getInt(SpfConfig.GLOBAL_SPF_PROFILE_GPU_LIMIT, 0),
            liteMode = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_LITE, false),
            governor = spf.getString(SpfConfig.GLOBAL_SPF_PROFILE_GOVERNOR, "") ?: "",
            ioScheduler = spf.getString(SpfConfig.GLOBAL_SPF_PROFILE_IOSCHED, "") ?: "",
            pidPriority = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_PID_PRIORITY, true),
            dndOnGame = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_DND_GAME, false),
            gamePreload = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_PRELOAD, false),
            preloadBudgetMb = spf.getInt(SpfConfig.GLOBAL_SPF_PROFILE_PRELOAD_BUDGET, 500),
            bypassChargeInGame = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_BYPASS_GAME, false),
            extraTweaks = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_EXTRA_TWEAKS, false),
            gameDownscale = spf.getInt(SpfConfig.GLOBAL_SPF_PROFILE_GAME_DOWNSCALE, 0),
            gameTargetFps = spf.getInt(SpfConfig.GLOBAL_SPF_PROFILE_GAME_FPS, 0),
            gameRenderer = spf.getString(SpfConfig.GLOBAL_SPF_PROFILE_GAME_RENDERER, "") ?: "",
            dropCachesOnGame = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_DROP_CACHES, false),
            qualcommBus = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_QCOM_BUS, false),
            qualcommGpu = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_QCOM_GPU, false),
            qualcommGpuPowersave = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_QCOM_GPU_PS, false),
            govTunes = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_GOV_TUNES, false),
            stopTrace = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_STOP_TRACE, false),
            stopLoggers = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_STOP_LOGGERS, false),
            globalRenderer = spf.getString(SpfConfig.GLOBAL_SPF_PROFILE_GLOBAL_RENDERER, "") ?: ""
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
            restoreGameRenderer()
            SceneStatus.write(mode, packageName, game)
            return
        }

        val env = StringBuilder()
        env.append("export SCENE_MODE=").append(ShellEscape.quote(mode)).append("\n")
        env.append("export SCENE_LIMIT_PERCENT=").append(ShellEscape.quote(config.limitPercent.toString())).append("\n")
        env.append("export SCENE_GPU_LIMIT=").append(ShellEscape.quote(config.gpuLimitPercent.toString())).append("\n")
        env.append("export SCENE_LITE=").append(ShellEscape.quote(if (effective.liteMode) "1" else "0")).append("\n")
        env.append("export SCENE_GOVERNOR=").append(ShellEscape.quote(config.governor)).append("\n")
        env.append("export SCENE_IOSCHED=").append(ShellEscape.quote(config.ioScheduler)).append("\n")
        env.append("export SCENE_PID=").append(ShellEscape.quote(if (config.pidPriority) "1" else "0")).append("\n")
        env.append("export SCENE_GAME_PKG=").append(ShellEscape.quote(if (game) packageName else "")).append("\n")
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
        } else {
            if (effective.bypassChargeInGame && BypassCharge.isAuto()) {
                // Only release the auto path; a manual toggle or the charge
                // protection level keeps their own reason.
                BypassCharge.setReason(BypassCharge.REASON_GAME, false)
            }
            restoreGameRenderer()
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
