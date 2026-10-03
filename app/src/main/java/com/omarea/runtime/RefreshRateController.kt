package com.omarea.runtime

import android.content.Context
import com.omarea.common.shell.ShellLog
import com.omarea.engine.ProfileController
import com.omarea.engine.RootShell
import com.omarea.util.CheckRootStatus

/**
 * Per-app refresh-rate override (SurfaceFlinger display mode).
 *
 * AZenith-derived idea (docs/ATTRIBUTION.md): the per-app picker persists the
 * chosen SF mode id, re-applies it on every app switch, and restores the mode
 * that was active before the first override when leaving the override apps
 * (or on engine OFF / TRUE OFF). Display state only — a reboot resets it.
 *
 * Responsibility: list, persist, apply/restore the SF mode.
 * Non-goals: picker layout/selection (ui/popup).
 */
object RefreshRateController {

    /** One supported SurfaceFlinger display mode. */
    data class DisplayMode(val id: Int, val hz: Int)

    private const val PREFS = "powercfg_refresh"
    private const val KEY_SAVED = "saved_mode_id"

    fun setOverride(context: Context, packageName: String, modeId: Int) {
        val app = context.applicationContext
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains(KEY_SAVED)) {
            activeModeId()?.let { p.edit().putInt(KEY_SAVED, it).apply() }
        }
        p.edit().putInt(packageName, modeId).apply()
        // Engine OFF = stock: store the choice but do not touch the display.
        if (allowed(app)) apply(modeId)
        DisplayRestart.maybeRestart(app, packageName)
        ShellLog.log("RefreshRate", "$packageName -> mode $modeId")
    }

    fun clearOverride(context: Context, packageName: String) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(packageName).apply()
        restore(app)
    }

    /** AppSwitchHandler entry point: apply the app's override or restore. */
    fun applyForApp(context: Context, packageName: String) {
        if (!allowed(context)) return
        val p = prefs(context)
        val mode = p.getInt(packageName, -1)
        if (mode >= 0) {
            apply(mode)
        } else {
            restore(context)
        }
    }

    /** Restore the pre-override mode (own change; allowed on any exit path). */
    fun restore(context: Context) {
        val p = prefs(context)
        val saved = p.getInt(KEY_SAVED, -1)
        if (saved < 0) return
        apply(saved)
        p.edit().remove(KEY_SAVED).apply()
        ShellLog.log("RefreshRate", "restored mode $saved")
    }

    /** Currently active SF display mode id (null when unreadable). */
    fun activeModeId(): Int? = try {
        val output = RootShell.run("dumpsys display").trim()
        Regex("mActiveSfDisplayMode=DisplayMode\\{id=([0-9]+)")
            .find(output)?.groupValues?.getOrNull(1)?.toIntOrNull()
    } catch (_: Exception) {
        null
    }

    /**
     * Supported display modes, highest refresh rate first (empty when dumpsys
     * is unreadable — the picker hides itself then). Replaces the old
     * kr-script/display/display_modes.sh asset, which no longer ships.
     */
    fun listModes(): List<DisplayMode> = try {
        parseModes(RootShell.run("dumpsys display"))
    } catch (_: Exception) {
        emptyList()
    }

    /** Pure `dumpsys display` parser (id → rounded Hz, deduped, Hz desc). */
    fun parseModes(output: String): List<DisplayMode> =
        Regex("DisplayMode\\{id=([0-9]+)[^}]*refreshRate=([0-9.]+)")
            .findAll(output)
            .mapNotNull { m ->
                val id = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val hz = m.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
                DisplayMode(id, Math.round(hz).toInt())
            }
            .distinctBy { it.id }
            .sortedByDescending { it.hz }
            .toList()

    private fun allowed(context: Context): Boolean =
        !TrueOff.isOff(context) &&
            !ProfileController.isEngineOff(context) &&
            CheckRootStatus.isAvailable()

    private fun apply(modeId: Int) {
        RootShell.run("service call SurfaceFlinger 1035 i32 $modeId")
        // AZenith keeps MIUI's own props in sync so the ROM does not undo the
        // choice on the next display event.
        refreshHzForMode(modeId)?.let { hz ->
            RootShell.run(
                "setprop persist.vendor.display.refresh_rate $hz; " +
                    "setprop persist.sys.display.refresh_rate $hz"
            )
        }
    }

    /** Refresh rate (Hz) of an SF display mode id, from dumpsys display. */
    fun refreshHzForMode(modeId: Int): Int? = try {
        val output = RootShell.run("dumpsys display").trim()
        Regex("DisplayMode\\{id=$modeId[^}]*refreshRate=([0-9.]+)")
            .find(output)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let { Math.round(it).toInt() }
    } catch (_: Exception) {
        null
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
