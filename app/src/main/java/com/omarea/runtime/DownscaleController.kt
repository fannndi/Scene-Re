package com.omarea.runtime

import android.content.Context
import com.omarea.common.shell.ShellLog
import com.omarea.engine.ProfileController
import com.omarea.engine.RootShell
import com.omarea.util.CheckRootStatus

/**
 * Per-app resolution downscale via the platform Game Mode API
 * (AZenith-derived, docs/ATTRIBUTION.md).
 *
 * `cmd game downscale <ratio|disable> <pkg>` is probe-verified on this ROM.
 * The choice is persisted per app (`powercfg_display` prefs), applied on app
 * switch while the engine can act, and reset on engine OFF / TRUE OFF — the
 * platform setting survives reboots, so it must never be left behind.
 *
 * Responsibility: persist + apply/reset the platform downscale.
 * Non-goals: deciding (pure [DownscalePolicy]), app detection.
 */
object DownscaleController {

    const val OFF = "off"

    /** Ratios offered by the UI (platform command accepts 0.3–0.9). */
    val RATIOS = listOf(OFF, "0.9", "0.8", "0.7", "0.6")

    private const val PREFS = "powercfg_display"

    fun ratioFor(context: Context, packageName: String): String =
        prefs(context).getString(packageName, OFF) ?: OFF

    fun hasRatio(context: Context, packageName: String): Boolean =
        ratioFor(context, packageName) != OFF

    fun isEnabled(context: Context): Boolean = prefs(context).all.isNotEmpty()

    /** Stores the choice always; applies it only while the engine may act. */
    fun set(context: Context, packageName: String, ratio: String) {
        val app = context.applicationContext
        if (ratio == OFF) {
            prefs(app).edit().remove(packageName).apply()
            runCommand("cmd game downscale disable $packageName")
        } else {
            prefs(app).edit().putString(packageName, ratio).apply()
            if (allowed(app)) runCommand("cmd game downscale $ratio $packageName")
        }
        // Mirror the package list for the uninstall guard.
        runCatching { SceneGuard.syncJournal(app) }
        // Optional: restart the foreground app so the change takes effect.
        DisplayRestart.maybeRestart(app, packageName)
        ShellLog.log("Downscale", "$packageName -> $ratio")
    }

    /** AppSwitchHandler entry point. */
    fun applyForApp(context: Context, packageName: String) {
        val ratio = ratioFor(context, packageName)
        if (DownscalePolicy.shouldApply(
                hasRatio = ratio != OFF,
                engineOff = ProfileController.isEngineOff(context),
                trueOff = TrueOff.isOff(context),
                rootAvailable = CheckRootStatus.isAvailable()
            )
        ) {
            runCommand("cmd game downscale $ratio $packageName")
        }
    }

    /** Disable every stored downscale (engine OFF / TRUE OFF / cleanup). */
    fun resetAll(context: Context) {
        val app = context.applicationContext
        val p = prefs(app)
        val packages = p.all.keys.toList()
        if (packages.isEmpty()) return
        for (pkg in packages) {
            runCommand("cmd game downscale disable $pkg")
        }
        p.edit().clear().apply()
        runCatching { SceneGuard.syncJournal(app) }
        ShellLog.log("Downscale", "reset ${packages.size} app(s)")
    }

    /** Packages currently stored (journal mirror for uninstall cleanup). */
    fun packages(context: Context): List<String> = prefs(context).all.keys.toList()

    private fun allowed(context: Context): Boolean =
        !TrueOff.isOff(context) &&
            !ProfileController.isEngineOff(context) &&
            CheckRootStatus.isAvailable()

    private fun runCommand(cmd: String) {
        runCatching { RootShell.run(cmd) }
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
