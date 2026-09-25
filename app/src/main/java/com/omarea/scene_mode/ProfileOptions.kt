package com.omarea.scene_mode

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.provider.Settings
import com.omarea.common.shared.FileWrite
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.ShellEscape
import com.omarea.store.SpfConfig
import com.omarea.utils.SceneLog

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

    /** True while a game package is in the foreground. */
    @Volatile
    var gameActive: Boolean = false
        private set

    data class Config(
        val enabled: Boolean,
        val limitPercent: Int,
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

    private fun ensureScript(context: Context): String? {
        scriptPath?.let { return it }
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
        // The global renderer is a standing override, applied even while the
        // rest of the options layer is switched off, then reverted on reset.
        setGlobalRenderer(if (config.enabled) config.globalRenderer else "")
        if (!config.enabled) {
            return
        }
        val effective = loadForApp(context, packageName, config)
        val script = ensureScript(context) ?: return
        val boostScript = ensureBoostScript(context)
        val game = isGame(context, packageName)

        if (effective.disabled) {
            // The user opted this app out of the options layer entirely: undo
            // what it applied and let the platform profile run alone.
            resetScripts(script, boostScript)
            gameActive = false
            updateDnd(context, false, effective)
            if (BypassCharge.isAuto()) {
                BypassCharge.disable()
            }
            restoreGameRenderer()
            writeStatus(mode, packageName, game)
            return
        }

        val env = StringBuilder()
        env.append("export SCENE_MODE=").append(ShellEscape.quote(mode)).append("\n")
        env.append("export SCENE_LIMIT_PERCENT=").append(ShellEscape.quote(config.limitPercent.toString())).append("\n")
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
        writeStatus(mode, packageName, game)
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
                // Only release the auto path; a manual QS toggle is left alone.
                BypassCharge.disable()
            }
            restoreGameRenderer()
        }
    }

    /** Re-apply after the screen turns on, because vendors often reset caps. */
    fun reapply(context: Context) {
        val config = load(context)
        if (!config.enabled) {
            return
        }
        val mode = ModeSwitcher.getCurrentPowerMode()
        if (mode.isEmpty()) {
            return
        }
        apply(context, mode, "", config)
    }

    /** Undo the limiter, pinning and Qualcomm boost. */
    fun reset(context: Context) {
        val script = ensureScript(context) ?: return
        resetScripts(script, ensureBoostScript(context))
        gameActive = false
        setGlobalRenderer("")
        writeStatus(ModeSwitcher.getCurrentPowerMode(), "", false)
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
    // | Status files for external tooling                             |
    // +---------------------------------------------------------------+

    private const val STATUS_PROFILE = "/data/adb/scene/current_profile"
    private const val STATUS_GAME = "/data/adb/scene/gameinfo"

    /**
     * Publish the current profile and active game session in a stable file
     * interface (same concept as Encore Tweaks' addon API, Scene paths), so
     * scripts and other root tools can observe the state.
     */
    private fun writeStatus(mode: String, packageName: String, game: Boolean) {
        try {
            var gameInfo = "NULL 0 0"
            if (game && packageName.isNotEmpty()) {
                val ids = KeepShellPublic.doCmdSync(
                    "pidof " + ShellEscape.quote(packageName) + " 2> /dev/null | tr ' ' '\\n' | head -n 1\n" +
                        "stat -c %u " + ShellEscape.quote("/data/data/$packageName") + " 2> /dev/null"
                )
                val parts = ids.lines().map { it.trim() }.filter { it.isNotEmpty() }
                gameInfo = "$packageName ${parts.getOrElse(0) { "0" }} ${parts.getOrElse(1) { "0" }}"
            }
            val cmd = StringBuilder("mkdir -p /data/adb/scene\n")
            if (mode.isNotEmpty()) {
                cmd.append("echo ").append(ShellEscape.quote(mode)).append(" > ").append(STATUS_PROFILE).append("\n")
            }
            cmd.append("cat > ").append(STATUS_GAME).append(" << 'SCENE_STATUS_EOF'\n")
                .append(gameInfo).append("\nSCENE_STATUS_EOF")
            KeepShellPublic.doCmdSync(cmd.toString())
        } catch (ex: Exception) {
            SceneLog.e("ProfileOptions", "status write failed", ex)
        }
    }

    // +---------------------------------------------------------------+
    // | Per-game renderer                                              |
    // +---------------------------------------------------------------+

    private const val PROP_RENDERER_BACKUP = "vtools.scene.renderer.bak"
    private const val PROP_RENDERER_SET = "vtools.scene.renderer.set"
    private const val PROP_GLOBAL_RENDERER_BACKUP = "vtools.scene.renderer.gbak"
    private const val PROP_GLOBAL_RENDERER_SET = "vtools.scene.renderer.gset"

    /**
     * Switch the HWUI renderer for a game. The property is only read when a
     * process starts, so the game is restarted when it differs.
     *
     * The original value is remembered together with an explicit "set" flag,
     * because the stock value is often the empty string and an empty backup
     * alone cannot tell "never touched" from "was empty".
     */
    private fun applyGameRenderer(packageName: String, renderer: String) {
        val set = KeepShellPublic.doCmdSync("getprop $PROP_RENDERER_SET").trim() == "1"
        val current = KeepShellPublic.doCmdSync("getprop debug.hwui.renderer").trim()
        if (!set) {
            KeepShellPublic.doCmdSync("setprop $PROP_RENDERER_BACKUP " + ShellEscape.quote(current))
            KeepShellPublic.doCmdSync("setprop $PROP_RENDERER_SET 1")
        }
        if (current == renderer) {
            return
        }
        KeepShellPublic.doCmdSync("setprop debug.hwui.renderer " + ShellEscape.quote(renderer))
        // Restart the game so it picks the renderer up.
        KeepShellPublic.doCmdSync(
            "am force-stop " + ShellEscape.quote(packageName) + "\n" +
                "sleep 1\n" +
                "monkey -p " + ShellEscape.quote(packageName) + " -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1"
        )
        SceneLog.i("ProfileOptions", "game renderer $renderer applied to $packageName")
    }

    private fun restoreGameRenderer() {
        val set = KeepShellPublic.doCmdSync("getprop $PROP_RENDERER_SET").trim() == "1"
        if (!set) {
            return
        }
        val backup = KeepShellPublic.doCmdSync("getprop $PROP_RENDERER_BACKUP").trim()
        KeepShellPublic.doCmdSync("setprop debug.hwui.renderer " + ShellEscape.quote(backup))
        KeepShellPublic.doCmdSync("setprop $PROP_RENDERER_BACKUP \"\"")
        KeepShellPublic.doCmdSync("setprop $PROP_RENDERER_SET 0")
    }

    /**
     * Standing renderer override for every app (AZenith global renderer).
     * Restores the pre-Scene value when the target is empty.
     */
    private fun setGlobalRenderer(target: String) {
        try {
            val set = KeepShellPublic.doCmdSync("getprop $PROP_GLOBAL_RENDERER_SET").trim() == "1"
            if (target.isEmpty()) {
                if (!set) {
                    return
                }
                val backup = KeepShellPublic.doCmdSync("getprop $PROP_GLOBAL_RENDERER_BACKUP").trim()
                KeepShellPublic.doCmdSync("setprop debug.hwui.renderer " + ShellEscape.quote(backup))
                KeepShellPublic.doCmdSync("setprop $PROP_GLOBAL_RENDERER_BACKUP \"\"")
                KeepShellPublic.doCmdSync("setprop $PROP_GLOBAL_RENDERER_SET 0")
                return
            }
            if (!set) {
                val current = KeepShellPublic.doCmdSync("getprop debug.hwui.renderer").trim()
                KeepShellPublic.doCmdSync("setprop $PROP_GLOBAL_RENDERER_BACKUP " + ShellEscape.quote(current))
                KeepShellPublic.doCmdSync("setprop $PROP_GLOBAL_RENDERER_SET 1")
            }
            val current = KeepShellPublic.doCmdSync("getprop debug.hwui.renderer").trim()
            if (current != target) {
                KeepShellPublic.doCmdSync("setprop debug.hwui.renderer " + ShellEscape.quote(target))
            }
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
