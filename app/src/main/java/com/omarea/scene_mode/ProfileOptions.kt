package com.omarea.scene_mode

import android.content.Context
import android.content.pm.ApplicationInfo
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
        val extraTweaks: Boolean
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
            extraTweaks = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_EXTRA_TWEAKS, false)
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

    /** Whether the package is declared as a game by its own manifest. */
    fun isGame(context: Context, packageName: String?): Boolean {
        if (packageName.isNullOrEmpty()) {
            return false
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
        if (!config.enabled) {
            return
        }
        val script = ensureScript(context) ?: return
        val game = isGame(context, packageName)

        val env = StringBuilder()
        env.append("export SCENE_MODE=").append(ShellEscape.quote(mode)).append("\n")
        env.append("export SCENE_LIMIT_PERCENT=").append(ShellEscape.quote(config.limitPercent.toString())).append("\n")
        env.append("export SCENE_LITE=").append(ShellEscape.quote(if (config.liteMode) "1" else "0")).append("\n")
        env.append("export SCENE_GOVERNOR=").append(ShellEscape.quote(config.governor)).append("\n")
        env.append("export SCENE_IOSCHED=").append(ShellEscape.quote(config.ioScheduler)).append("\n")
        env.append("export SCENE_PID=").append(ShellEscape.quote(if (config.pidPriority) "1" else "0")).append("\n")
        env.append("export SCENE_GAME_PKG=").append(ShellEscape.quote(if (game) packageName else "")).append("\n")
        env.append("export SCENE_EXTRA_TWEAKS=").append(ShellEscape.quote(if (config.extraTweaks) "1" else "0")).append("\n")
        env.append("sh ").append(ShellEscape.quote(script)).append(" > /dev/null 2>&1")

        KeepShellPublic.doCmdSync(env.toString())

        gameActive = game
        updateDnd(context, game, config)

        if (game) {
            if (config.bypassChargeInGame) {
                BypassCharge.enableIfNeeded(context)
            }
            if (config.gamePreload) {
                GamePreloader.preload(context, packageName, config.preloadBudgetMb)
            }
        } else if (config.bypassChargeInGame) {
            BypassCharge.disable()
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

    /** Undo the limiter and min-frequency pinning. */
    fun reset(context: Context) {
        val script = ensureScript(context) ?: return
        KeepShellPublic.doCmdSync("export SCENE_RESET=1\nsh " + ShellEscape.quote(script) + " > /dev/null 2>&1")
        gameActive = false
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
