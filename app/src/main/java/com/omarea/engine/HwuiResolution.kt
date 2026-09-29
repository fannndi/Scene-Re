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
}
