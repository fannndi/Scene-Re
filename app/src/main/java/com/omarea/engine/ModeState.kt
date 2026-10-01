package com.omarea.engine

/**
 * Pure resolution of the active mode id.
 *
 * The same value lives in three stores:
 *  - runtime cache  (`ModeSwitcher.currentPowercfg`, dies with the process)
 *  - kernel prop    (`vtools.powercfg`, survives process death, not reboot)
 *  - persisted pref (`GLOBAL_SPF_LAST_MODE`, survives reboot)
 *
 * The active mode is the first non-empty store, in that order. Values are
 * returned verbatim (the legacy `fast` id is NOT rewritten here): the UI
 * compares raw runtime ids while every JSON lookup canonicalizes through
 * [ProfileKey].
 *
 * Responsibility: fallback order + labels for the special states.
 * Non-goals: reading prefs/props (callers own that), applying modes.
 */
object ModeState {

    /** First non-empty source wins (runtime → prop → persisted). */
    fun resolve(runtime: String, prop: String, saved: String): String {
        if (runtime.isNotEmpty()) return runtime
        if (prop.isNotEmpty()) return prop
        return saved
    }

    /**
     * Label for Home/notification. Engine OFF wins over the remembered mode
     * (a mode may still be stored while the engine applies nothing), and an
     * empty mode is the "global default" (init tuning only, no profile).
     *
     * @param nameOf maps a mode id to its display name (localized by caller).
     */
    fun displayName(mode: String, engineOff: Boolean, nameOf: (String) -> String): String = when {
        engineOff -> "Profiles OFF (stock)"
        mode.isEmpty() -> "Global Default"
        else -> nameOf(mode)
    }
}
