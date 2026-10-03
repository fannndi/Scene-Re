package com.omarea.engine

/**
 * The four states every surface (top-bar chip, Home banner, notification,
 * QS tile) must agree on: "is Scene tuning anything right now?".
 *
 * Precedence matches the writers' rules: TRUE OFF wins over everything
 * (hard rule 14), then Monitor mode (no root = zero writers, hard rule 16),
 * then the engine switch.
 *
 * Responsibility: resolve the state from already-read flags.
 * Non-goals: reading prefs/props, localized labels (callers own those).
 */
enum class EngineState {
    TUNING,
    STOCK,
    TRUE_OFF,
    MONITOR;

    companion object {
        fun resolve(rootAvailable: Boolean, trueOff: Boolean, engineOff: Boolean): EngineState = when {
            trueOff -> TRUE_OFF
            !rootAvailable -> MONITOR
            engineOff -> STOCK
            else -> TUNING
        }
    }
}
