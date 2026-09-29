package com.omarea.engine

import android.content.Context
import com.omarea.engine.HwuiResolution
import com.omarea.engine.ProfileKey
import com.omarea.engine.TuningRepository
import com.omarea.engine.PropShell
import com.omarea.engine.RootShell
import com.omarea.util.PlatformUtils
import com.omarea.util.PropsUtils
import com.omarea.data.SpfConfig

/**
 * Single owner of the HWUI rendering properties.
 *
 * Effective value resolution (highest priority first):
 *   1. per-app override   (SpfConfig.HWUI_SPF, set in app details)
 *   2. per-profile value  ("hwui" object of the active tuning profile)
 *   3. system default     (properties removed)
 * While the profile engine is OFF everything resolves to the system default.
 *
 * Responsibility: resolve + apply debug.hwui.renderer / ro.hwui.use_vulkan.
 * Non-goals: kernel tuning, UI.
 */
object HwuiController {

    private const val PROP_RENDERER = "debug.hwui.renderer"
    private const val PROP_VULKAN = "ro.hwui.use_vulkan"
    private const val KEY_RENDERER = "renderer"
    private const val KEY_VULKAN = "vulkan"

    /** Mode/app are read from the props ModeSwitcher keeps in sync. */
    private const val MODE_PROP = "vtools.powercfg"
    private const val APP_PROP = "vtools.powercfg_app"

    // ------------------------------------------------------------ per-app IO
    fun getRenderer(context: Context, pkg: String): String =
        prefs(context).getString("${KEY_RENDERER}_$pkg", "") ?: ""

    fun getVulkan(context: Context, pkg: String): String =
        prefs(context).getString("${KEY_VULKAN}_$pkg", "") ?: ""

    fun setRenderer(context: Context, pkg: String, value: String) =
        put(context, "${KEY_RENDERER}_$pkg", value)

    fun setVulkan(context: Context, pkg: String, value: String) =
        put(context, "${KEY_VULKAN}_$pkg", value)

    private fun put(context: Context, key: String, value: String) {
        val edit = prefs(context).edit()
        if (value.isEmpty()) edit.remove(key) else edit.putString(key, value)
        edit.apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(SpfConfig.HWUI_SPF, Context.MODE_PRIVATE)

    // ---------------------------------------------------------- UI cycle API
    fun nextRenderer(current: String): String = when (current) {
        "" -> "opengl"
        "opengl" -> "skiagl"
        "skiagl" -> "skiavk"
        else -> ""
    }

    fun nextVulkan(current: String): String = if (current == "true") "" else "true"

    // -------------------------------------------------------------- applying
    /** Applies the effective overrides for [pkg] (null = only profile/default). */
    fun applyForApp(context: Context, pkg: String?) {
        val renderer = resolve(context, pkg, KEY_RENDERER)
        val vulkan = resolve(context, pkg, KEY_VULKAN)
        val script = buildString {
            append(
                if (renderer == null) PropShell.delete(PROP_RENDERER)
                else PropShell.set(PROP_RENDERER, renderer)
            )
            append("\n")
            append(
                if (vulkan == null) PropShell.delete(PROP_VULKAN)
                else PropShell.set(PROP_VULKAN, vulkan)
            )
        }
        RootShell.run(script)
    }

    /** Re-applies overrides for the app currently in the foreground. */
    fun applyActive(context: Context) =
        applyForApp(context, PropsUtils.getProp(APP_PROP))

    /** Removes all overrides (profile engine OFF / release path). */
    fun clear(context: Context) {
        RootShell.run("${PropShell.delete(PROP_RENDERER)}\n${PropShell.delete(PROP_VULKAN)}")
    }

    // ------------------------------------------------------------ resolution
    /** Effective renderer, or null for the system default. */
    fun resolveRenderer(context: Context, pkg: String?): String? =
        resolve(context, pkg, KEY_RENDERER)

    /** Effective vulkan flag, or null for the system default. */
    fun resolveVulkan(context: Context, pkg: String?): String? =
        resolve(context, pkg, KEY_VULKAN)

    private fun resolve(context: Context, pkg: String?, key: String): String? {
        val engineOff = isEngineOff(context)

        val perApp = if (!pkg.isNullOrEmpty()) {
            prefs(context).getString("${key}_$pkg", "") ?: ""
        } else ""

        var profileValue: String? = null
        if (!engineOff) {
            val platform = PlatformUtils().getCPUName()
            val json = TuningRepository.read(context, platform)
            val mode = ProfileKey.canonical(PropsUtils.getProp(MODE_PROP))
            profileValue = json?.let {
                ProfileKey.profile(it.optJSONObject("profiles"), mode)
                    ?.optJSONObject("hwui")?.optString(key, "")
            }
        }
        return HwuiResolution.resolve(engineOff, perApp, profileValue)
    }

    private fun isEngineOff(context: Context): Boolean =
        context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
            .getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_OFF, false)
}
