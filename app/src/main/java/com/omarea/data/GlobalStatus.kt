package com.omarea.data

import android.os.BatteryManager
import com.omarea.library.shell.BatteryUtils
import com.omarea.permissions.CheckRootStatus.Companion.lastCheckResult

object GlobalStatus {
    var temperatureCurrent = -1.0
        private set
    private var batteryTempTime = 0L
    fun setBatteryTemperature(temperature: Double) {
        batteryTempTime = System.currentTimeMillis()
        temperatureCurrent = temperature
    }

    /**
     * Get real-time temperature (if a long time has passed since the last update, re-read temperature with ROOT)
     */
    fun updateBatteryTemperature(): Double {
        // throttle updates to >5 seconds
        if (lastCheckResult && System.currentTimeMillis() - 5000 >= batteryTempTime) {
            // update battery temperature
            val temperature = BatteryUtils.getBatteryTemperature().temperature
            if (temperature > 10 && temperature < 100) {
                setBatteryTemperature(temperature)
            }
        }
        return temperatureCurrent
    }

    var batteryCapacity = -1
    var batteryVoltage = -1.0
    var batteryCurrentNow: Long = -1
    var batteryStatus = BatteryManager.BATTERY_STATUS_UNKNOWN
    var lastPackageName = ""
}