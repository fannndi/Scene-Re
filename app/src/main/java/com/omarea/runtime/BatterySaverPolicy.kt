package com.omarea.runtime

/**
 * Battery-saver overlay decision table (pure, JVM-tested).
 *
 * Encore semantics (docs/ATTRIBUTION.md): when the system battery saver turns
 * on, the engine switches to `powersave`; when it turns off, the mode that was
 * active before the overlay returns. Engine OFF, TRUE OFF and Monitor mode
 * never write — the decision simply says NONE, and an explicit user mode change
 * ends the overlay (handled by [ModeSwitcher]).
 *
 * Responsibility: decide, nothing else.
 * Non-goals: reading device state / applying modes ([BatterySaverMode]).
 */
object BatterySaverPolicy {

    enum class Action { NONE, APPLY_OVERLAY, RESTORE_BASE }

    fun decide(
        engineOff: Boolean,
        trueOff: Boolean,
        rootAvailable: Boolean,
        enabled: Boolean,
        saverOn: Boolean,
        overlayActive: Boolean
    ): Action = when {
        // Never write while the engine could not — TRUE OFF wins over all.
        engineOff || trueOff || !rootAvailable -> Action.NONE
        // Disabled: never engage a new overlay, but undo one Scene started
        // (otherwise turning the feature off mid-overlay strands the mode).
        !enabled -> if (overlayActive) Action.RESTORE_BASE else Action.NONE
        saverOn && !overlayActive -> Action.APPLY_OVERLAY
        !saverOn && overlayActive -> Action.RESTORE_BASE
        else -> Action.NONE
    }
}
