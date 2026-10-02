package com.omarea.runtime

/**
 * Bypass-charging decision table (pure, JVM-tested).
 *
 * User-approved exception to the "charging is read-only" rule (AGENTS.md
 * rule 12): the opt-in controller stops battery charging at a threshold while
 * the device stays powered. It is the ONLY charge writer besides the legacy
 * [ChargeStockRestorer].
 *
 * DISABLE is also returned when root is gone so the change heals as soon as a
 * root trigger runs again; ENABLE always requires root.
 */
object BypassChargePolicy {

    enum class Action { NONE, ENABLE, DISABLE }

    fun decide(
        enabled: Boolean,
        engineOff: Boolean,
        trueOff: Boolean,
        rootAvailable: Boolean,
        charging: Boolean,
        level: Int,
        threshold: Int,
        active: Boolean
    ): Action = when {
        active && (!enabled || engineOff || trueOff || !charging || level < threshold) -> Action.DISABLE
        !active && enabled && !engineOff && !trueOff && rootAvailable &&
            charging && level >= threshold -> Action.ENABLE
        else -> Action.NONE
    }
}
