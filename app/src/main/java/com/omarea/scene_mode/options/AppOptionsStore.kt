package com.omarea.scene_mode.options

import android.content.Context
import com.omarea.store.SpfConfig

/**
 * Per-app overrides for the profile options layer, stored in a dedicated
 * preferences file so the powercfg per-app mode store stays untouched.
 *
 * A value of [FOLLOW] means "use the global setting".
 */
object AppOptionsStore {
    const val FOLLOW = -1

    data class Override(
        val enabled: Int = FOLLOW,
        val lite: Int = FOLLOW,
        val preload: Int = FOLLOW,
        val dnd: Int = FOLLOW,
        val bypass: Int = FOLLOW,
        val downscale: Int = FOLLOW,
        val fps: Int = FOLLOW,
        val renderer: String = RENDERER_FOLLOW,
        /** Display mode id to apply while the game runs ([FOLLOW] = untouched). */
        val refresh: Int = FOLLOW,
        /** cpu_boost input window while this game runs. */
        val cpuBoost: Int = FOLLOW,
        /** MIUI thermal mode forced while this game runs ([FOLLOW] = global). */
        val miuiThermal: Int = FOLLOW,
        /** Use MIUI's own target FPS as the refresh default for this game. */
        val miuiRefresh: Int = FOLLOW
    ) {
        fun isEmpty(): Boolean =
            enabled == FOLLOW && lite == FOLLOW && preload == FOLLOW && dnd == FOLLOW &&
                bypass == FOLLOW && downscale == FOLLOW && fps == FOLLOW &&
                renderer == RENDERER_FOLLOW && refresh == FOLLOW && cpuBoost == FOLLOW &&
                miuiThermal == FOLLOW && miuiRefresh == FOLLOW
    }

    const val RENDERER_FOLLOW = ""
    const val RENDERER_OFF = "off"

    private fun spf(context: Context) =
        context.getSharedPreferences(SpfConfig.APP_PROFILE_OPTIONS_SPF, Context.MODE_PRIVATE)

    fun load(context: Context, packageName: String): Override {
        if (packageName.isEmpty()) {
            return Override()
        }
        val prefs = spf(context)
        return Override(
            enabled = prefs.getInt("$packageName.enabled", FOLLOW),
            lite = prefs.getInt("$packageName.lite", FOLLOW),
            preload = prefs.getInt("$packageName.preload", FOLLOW),
            dnd = prefs.getInt("$packageName.dnd", FOLLOW),
            bypass = prefs.getInt("$packageName.bypass", FOLLOW),
            downscale = prefs.getInt("$packageName.downscale", FOLLOW),
            fps = prefs.getInt("$packageName.fps", FOLLOW),
            renderer = prefs.getString("$packageName.renderer", RENDERER_FOLLOW) ?: RENDERER_FOLLOW,
            refresh = prefs.getInt("$packageName.refresh", FOLLOW),
            cpuBoost = prefs.getInt("$packageName.cpu_boost", FOLLOW),
            miuiThermal = prefs.getInt("$packageName.miui_thermal", FOLLOW),
            miuiRefresh = prefs.getInt("$packageName.miui_refresh", FOLLOW)
        )
    }

    fun save(context: Context, packageName: String, override: Override) {
        if (packageName.isEmpty()) {
            return
        }
        val editor = spf(context).edit()
        if (override.isEmpty()) {
            editor
                .remove("$packageName.enabled")
                .remove("$packageName.lite")
                .remove("$packageName.preload")
                .remove("$packageName.dnd")
                .remove("$packageName.bypass")
                .remove("$packageName.downscale")
                .remove("$packageName.fps")
                .remove("$packageName.renderer")
                .remove("$packageName.refresh")
                .remove("$packageName.cpu_boost")
                .remove("$packageName.miui_thermal")
                .remove("$packageName.miui_refresh")
        } else {
            editor
                .putInt("$packageName.enabled", override.enabled)
                .putInt("$packageName.lite", override.lite)
                .putInt("$packageName.preload", override.preload)
                .putInt("$packageName.dnd", override.dnd)
                .putInt("$packageName.bypass", override.bypass)
                .putInt("$packageName.downscale", override.downscale)
                .putInt("$packageName.fps", override.fps)
                .putString("$packageName.renderer", override.renderer)
                .putInt("$packageName.refresh", override.refresh)
                .putInt("$packageName.cpu_boost", override.cpuBoost)
                .putInt("$packageName.miui_thermal", override.miuiThermal)
                .putInt("$packageName.miui_refresh", override.miuiRefresh)
        }
        editor.apply()
    }
}
