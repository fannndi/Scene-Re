package com.omarea.util.battery

/**
 * Pure current-normalisation helpers (JVM-testable).
 *
 * The kernel reports `current_now` with vendor-specific polarity; the app works
 * in one canonical convention: **positive = charging, negative = discharging**.
 */
object CurrentMath {

    /**
     * Derives the polarity calibration from a raw reading plus the charging
     * state. Returns [current] when the observation cannot decide.
     *
     * @param current previous calibration: true = raw positive means charging.
     */
    fun calibrate(raw: Long, isCharging: Boolean, isDischarging: Boolean, current: Boolean?): Boolean? = when {
        raw == 0L -> current
        isCharging -> raw > 0
        isDischarging -> raw < 0
        else -> current
    }

    fun canonical(raw: Long, chargingPositive: Boolean?): Long =
        if (chargingPositive == false) -raw else raw

    /** Median of the window; null for an empty window. */
    fun median(window: Collection<Long>): Long? {
        if (window.isEmpty()) return null
        val sorted = window.sorted()
        val size = sorted.size
        return if (size % 2 == 1) {
            sorted[size / 2]
        } else {
            (sorted[size / 2 - 1] + sorted[size / 2]) / 2
        }
    }
}
