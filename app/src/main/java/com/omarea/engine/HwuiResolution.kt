package com.omarea.engine

/**
 * Pure resolution rule for the effective HWUI values (unit-tested).
 *
 * Priority: per-app override > active profile value > system default.
 * While the profile engine is OFF everything resolves to the default (null).
 */
object HwuiResolution {
    fun resolve(engineOff: Boolean, perApp: String?, profileValue: String?): String? {
        if (engineOff) return null
        if (!perApp.isNullOrEmpty()) return perApp
        return profileValue?.takeIf { it.isNotEmpty() }
    }

    /**
     * Effective backend after the renderer/vulkan props are combined
     * (SkiaShift-derived): an explicit `debug.hwui.renderer` wins over the
     * boot-time `ro.hwui.use_vulkan` flag; with neither set the ROM default
     * (SkiaGL on Android 12) applies.
     */
    fun isVulkanEffective(renderer: String?, vulkan: String?): Boolean = when (renderer) {
        "skiavk" -> true
        "skiagl", "opengl" -> false
        else -> vulkan == "true"
    }
}
