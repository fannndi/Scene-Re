package com.omarea.util.measure

import kotlin.math.abs

/**
 * Thermal-zone value decoding (pure, JVM-testable).
 *
 * Thermal zones on this platform report millidegrees (`59100` = 59.1°C) and a
 * few report deci-degrees or plain degrees; some nodes matching a "temp path"
 * are actually hardware *levels* (not temperatures) and must be filtered by
 * plausibility.
 */
object ThermalMath {

    /** Decodes a raw thermal zone reading to Celsius. */
    fun toCelsius(raw: Double): Double = when {
        abs(raw) >= 1000.0 -> raw / 1000.0
        abs(raw) >= 100.0 -> raw / 10.0
        else -> raw
    }

    fun isPlausible(celsius: Double): Boolean = celsius > -40.0 && celsius < 150.0

    /** Convenience: decode + plausibility in one step. */
    fun decodePlausible(raw: Double?): Double? {
        if (raw == null) return null
        val celsius = toCelsius(raw)
        return celsius.takeIf { isPlausible(it) }
    }

    /** Formats a decoded temperature as `59.1°C`. */
    fun format(celsius: Double?): String =
        if (celsius == null) "--" else String.format(java.util.Locale.US, "%.1f°C", celsius)
}
