package com.omarea.runtime

import android.os.BatteryManager
import java.util.Locale
import kotlin.math.abs

/**
 * Pure formatting helpers for the status notification (JVM-tested).
 *
 * Responsibility: turn raw battery readings into the notification's short
 * strings + classify the charge state. No Android calls beyond compile-time
 * constants; no context.
 */
object NotificationFormat {

    enum class BatteryState { CHARGING, DISCHARGING, FULL, NOT_CHARGING, UNKNOWN }

    fun batteryState(status: Int): BatteryState = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> BatteryState.CHARGING
        BatteryManager.BATTERY_STATUS_DISCHARGING -> BatteryState.DISCHARGING
        BatteryManager.BATTERY_STATUS_FULL -> BatteryState.FULL
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> BatteryState.NOT_CHARGING
        else -> BatteryState.UNKNOWN
    }

    /** "4.3W" from volts × milliamps; empty when no usable reading. */
    fun formatWatts(voltageV: Double, currentMa: Int): String {
        if (voltageV <= 0.0 || currentMa == 0) return ""
        val watts = abs(voltageV * currentMa / 1000.0)
        if (watts <= 0.0) return ""
        return if (watts >= 10) {
            String.format(Locale.US, "%.0fW", watts)
        } else {
            String.format(Locale.US, "%.1fW", watts)
        }
    }

    /** "1033mA · 4.3W" with graceful fallbacks (empty when nothing is valid). */
    fun currentLine(currentMa: Int, valid: Boolean, watts: String): String {
        val current = if (valid) "${currentMa}mA" else ""
        return listOf(current, watts).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /** "92% · 34.2°C" with graceful fallbacks. */
    fun capacityLine(capacity: Int, tempC: Double): String {
        val parts = ArrayList<String>(2)
        if (capacity in 0..100) parts += "$capacity%"
        if (tempC > 0) parts += String.format(Locale.US, "%.1f°C", tempC)
        return parts.joinToString(" · ")
    }
}
