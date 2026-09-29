package com.omarea.core.profile

import org.json.JSONObject

/**
 * Canonical identifiers for the four user-selectable profiles plus the
 * stock "release" profile.
 *
 * The runtime keeps the legacy mode id "fast" (stored in user preferences),
 * while the tuning JSON names the same profile "custom". Every lookup must
 * go through this object so both keys keep working.
 *
 * Responsibility: profile id canonicalization and typed profile lookup.
 * Non-goals: reading files, executing shell.
 * Invariants:
 *  - [ALL] lists the canonical keys in UI order (release is internal).
 *  - [canonical] accepts both "custom" and the legacy "fast".
 */
object ProfileKey {
    const val POWERSAVE = "powersave"
    const val BALANCE = "balance"
    const val PERFORMANCE = "performance"
    const val CUSTOM = "custom"
    const val RELEASE = "release"

    /** Legacy runtime mode id for [CUSTOM] (kept for stored preferences). */
    const val LEGACY_FAST = "fast"

    /** Canonical keys shown to the user, in order. */
    val ALL = listOf(POWERSAVE, BALANCE, PERFORMANCE, CUSTOM)

    /** Canonical keys including the stock release profile. */
    val ALL_WITH_RELEASE = ALL + RELEASE

    /** Maps any accepted id to the canonical JSON key. */
    fun canonical(mode: String): String = when (mode) {
        LEGACY_FAST -> CUSTOM
        else -> mode
    }

    /** Returns the profile object in [profiles] for [mode], if present. */
    fun profile(profiles: JSONObject?, mode: String): JSONObject? {
        profiles ?: return null
        val canonical = canonical(mode)
        val direct = profiles.optJSONObject(canonical)
        if (direct != null) return direct
        // A user copy may still carry the legacy key for the custom profile.
        if (canonical == CUSTOM) return profiles.optJSONObject(LEGACY_FAST)
        return null
    }
}
