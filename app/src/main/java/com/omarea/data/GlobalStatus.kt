package com.omarea.data

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.BatteryManager.BATTERY_STATUS_UNKNOWN
import android.os.SystemClock
import com.omarea.Scene
import com.omarea.util.BatteryUtils
import com.omarea.util.CheckRootStatus.Companion.lastCheckResult

/**
 * Cross-module runtime state (battery + foreground app).
 *
 * Temperature policy: the sticky battery broadcast (0.1°C resolution) is the
 * primary source - no shell; a root-shell read is only the fallback, and the
 * cached value is used while it is fresh. Values are validated on write.
 */
object GlobalStatus {
    @Volatile
    var temperatureCurrent = -1.0
        private set

    private var batteryTempUpdatedAt = 0L
    private const val FRESH_MS = 3000L

    /** Records a broadcast-derived temperature (deci-Celsius based). */
    fun setBatteryTemperature(temperature: Double) {
        if (temperature < -30.0 || temperature > 120.0) {
            return
        }
        batteryTempUpdatedAt = SystemClock.elapsedRealtime()
        temperatureCurrent = temperature
    }

    /**
     * Fresh battery temperature: recent cache -> sticky broadcast (no shell) ->
     * root shell fallback when permission exists.
     */
    fun updateBatteryTemperature(): Double {
        val now = SystemClock.elapsedRealtime()
        if (temperatureCurrent > 0 && now - batteryTempUpdatedAt < FRESH_MS) {
            return temperatureCurrent
        }
        stickyBatteryTemperature()?.let {
            setBatteryTemperature(it)
            return temperatureCurrent
        }
        if (lastCheckResult) {
            val fromShell = BatteryUtils.getBatteryTemperature().temperature
            if (fromShell > 10 && fromShell < 100) {
                setBatteryTemperature(fromShell)
            }
        }
        return temperatureCurrent
    }

    /** Battery temp from the sticky intent in deci-Celsius, or null. */
    private fun stickyBatteryTemperature(): Double? = try {
        val intent = Scene.context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val deci = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        if (deci > 30 && deci < 1200) deci / 10.0 else null
    } catch (ex: Exception) {
        null
    }

    var batteryCapacity = -1
    var batteryVoltage = -1.0
    var batteryCurrentNow: Long = -1
    var batteryStatus = BATTERY_STATUS_UNKNOWN
    var lastPackageName = ""
}
