package com.omarea.runtime

import android.content.Context
import android.os.PowerManager
import com.omarea.common.shell.ShellLog
import com.omarea.data.SpfConfig
import com.omarea.engine.ProfileController
import com.omarea.util.CheckRootStatus

/**
 * Battery-saver -> powersave overlay.
 *
 * While the system battery saver is active the engine runs `powersave`; when
 * it turns off the mode that was active before the overlay is re-applied.
 * Explicit user mode actions win and end the overlay ([ModeSwitcher]).
 *
 * Responsibility: observe saver state, remember/restore the base mode.
 * Non-goals: deciding what to do (pure [BatterySaverPolicy]), applying
 * profiles ([ModeSwitcher.applyOverlayMode]).
 */
object BatterySaverMode {

    /** Current system battery-saver state (false when unavailable). */
    fun isSaverOn(context: Context): Boolean = try {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
    } catch (_: Exception) {
        false
    }

    fun isOverlayActive(context: Context): Boolean =
        prefs(context).getBoolean(SpfConfig.GLOBAL_SPF_SAVER_OVERLAY, false)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(
            SpfConfig.GLOBAL_SPF_SAVER_OVERLAY_ENABLED,
            SpfConfig.GLOBAL_SPF_SAVER_OVERLAY_DEFAULT
        )

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)

    /** Base mode remembered when the overlay engaged (empty when inactive). */
    fun baseMode(context: Context): String =
        prefs(context).getString(SpfConfig.GLOBAL_SPF_SAVER_BASE_MODE, "").orEmpty()

    /**
     * An explicit user mode action owns the mode again: drop the overlay state
     * so the eventual saver-OFF does not restore a stale base mode.
     */
    internal fun clearOverlay(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(SpfConfig.GLOBAL_SPF_SAVER_OVERLAY, false)) {
            p.edit()
                .putBoolean(SpfConfig.GLOBAL_SPF_SAVER_OVERLAY, false)
                .remove(SpfConfig.GLOBAL_SPF_SAVER_BASE_MODE)
                .apply()
        }
    }

    /** State string for Diagnostics. */
    fun describe(context: Context): String {
        val p = prefs(context)
        val base = p.getString(SpfConfig.GLOBAL_SPF_SAVER_BASE_MODE, "").orEmpty()
        return buildString {
            append("saver=").append(if (isSaverOn(context)) "ON" else "OFF")
            append(" overlay=").append(if (isOverlayActive(context)) "active" else "inactive")
            if (base.isNotEmpty()) append(" base=$base")
        }
    }

    /**
     * Receiver / boot / app-open entry point. Never throws, never fights
     * engine OFF / TRUE OFF / Monitor mode ([BatterySaverPolicy] decides).
     */
    fun evaluate(context: Context) {
        val app = context.applicationContext
        runCatching {
            val action = BatterySaverPolicy.decide(
                engineOff = ProfileController.isEngineOff(app),
                trueOff = TrueOff.isOff(app),
                rootAvailable = CheckRootStatus.isAvailable(),
                enabled = isEnabled(app),
                saverOn = isSaverOn(app),
                overlayActive = isOverlayActive(app)
            )
            when (action) {
                BatterySaverPolicy.Action.APPLY_OVERLAY -> {
                    val base = ModeSwitcher.getCurrentPowerMode().ifEmpty { ModeSwitcher.savedMode() }
                    prefs(app).edit()
                        .putBoolean(SpfConfig.GLOBAL_SPF_SAVER_OVERLAY, true)
                        .putString(SpfConfig.GLOBAL_SPF_SAVER_BASE_MODE, base)
                        .apply()
                    if (base != ModeSwitcher.POWERSAVE) {
                        ModeSwitcher().applyOverlayMode(ModeSwitcher.POWERSAVE, keepSavedMode = true)
                    }
                    ShellLog.log("BatterySaver", "saver ON -> powersave (base=$base)")
                }
                BatterySaverPolicy.Action.RESTORE_BASE -> {
                    val base = prefs(app).getString(SpfConfig.GLOBAL_SPF_SAVER_BASE_MODE, "").orEmpty()
                    prefs(app).edit()
                        .putBoolean(SpfConfig.GLOBAL_SPF_SAVER_OVERLAY, false)
                        .remove(SpfConfig.GLOBAL_SPF_SAVER_BASE_MODE)
                        .apply()
                    if (base.isNotEmpty()) {
                        ModeSwitcher().applyOverlayMode(base)
                    }
                    ShellLog.log("BatterySaver", "saver OFF -> restore ${base.ifEmpty { "(none)" }}")
                }
                BatterySaverPolicy.Action.NONE -> Unit
            }
        }
    }

    /**
     * Boot: the applied state was re-derived from the saved mode, so any
     * overlay state from the previous boot is stale. Clear it and derive the
     * current truth (saver ON at boot -> powersave right away).
     */
    fun evaluateAtBoot(context: Context) {
        clearOverlay(context)
        evaluate(context)
    }
}
