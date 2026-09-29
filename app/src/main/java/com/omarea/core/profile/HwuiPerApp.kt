package com.omarea.core.profile

import android.content.Context
import com.omarea.common.shell.KeepShellPublic

/**
 * Per-app HWUI overrides (renderer / vulkan), stored in the "hwui" prefs.
 *
 * Applied on app switch:
 *  - debug.hwui.renderer   : normal prop, takes effect for freshly started
 *                            processes (force-close & relaunch the app)
 *  - ro.hwui.use_vulkan    : read-only prop — needs resetprop (APatch/Magisk).
 *                            Value change applies to processes started after.
 * Empty stored value = remove override (system default).
 */
object HwuiPerApp {

    private const val SPF = "hwui"

    private fun spf(context: Context) =
        context.getSharedPreferences(SPF, Context.MODE_PRIVATE)

    fun getRenderer(context: Context, pkg: String): String =
        spf(context).getString("renderer_$pkg", "") ?: ""

    fun getVulkan(context: Context, pkg: String): String =
        spf(context).getString("vulkan_$pkg", "") ?: ""

    fun setRenderer(context: Context, pkg: String, value: String) {
        if (value.isEmpty()) spf(context).edit().remove("renderer_$pkg").apply()
        else spf(context).edit().putString("renderer_$pkg", value).apply()
    }

    fun setVulkan(context: Context, pkg: String, value: String) {
        if (value.isEmpty()) spf(context).edit().remove("vulkan_$pkg").apply()
        else spf(context).edit().putString("vulkan_$pkg", value).apply()
    }

    // APatch ships resetprop at /data/adb/ap/bin/resetprop (not on PATH)
    private val RP = "\$(command -v resetprop 2>/dev/null || echo /data/adb/ap/bin/resetprop)"

    private fun setProp(prop: String, value: String): String =
        "$RP $prop '$value'"

    private fun deleteProp(prop: String): String =
        "$RP --delete $prop"

    /**
     * Called on app switch: applies this app's HWUI overrides, or clears the
     * props when the foreground app has no config (back to system default).
     */
    fun applyForApp(context: Context, pkg: String?) {
        if (pkg.isNullOrEmpty()) return
        val renderer = getRenderer(context, pkg)
        val vulkan = getVulkan(context, pkg)

        val cmd = StringBuilder()
        if (renderer.isNotEmpty()) {
            cmd.append(setProp("debug.hwui.renderer", renderer)).append("\n")
        } else {
            cmd.append(deleteProp("debug.hwui.renderer")).append("\n")
        }
        if (vulkan.isNotEmpty()) {
            cmd.append(setProp("ro.hwui.use_vulkan", vulkan)).append("\n")
        } else {
            cmd.append(deleteProp("ro.hwui.use_vulkan")).append("\n")
        }
        KeepShellPublic.doCmdSync(cmd.toString())
    }

    /** Cycle renderer value for the per-app editor UI. */
    fun nextRenderer(current: String): String = when (current) {
        "" -> "opengl"
        "opengl" -> "skiagl"
        "skiagl" -> "skiavk"
        else -> ""
    }

    /** Cycle vulkan value for the per-app editor UI. */
    fun nextVulkan(current: String): String = if (current == "true") "" else "true"
}
