package com.omarea.data

import android.os.BatteryManager
import com.omarea.library.shell.BatteryUtils
import com.omarea.permissions.CheckRootStatus.Companion.lastCheckResult
import com.omarea.vtools.privilege.PrivilegeManager

object GlobalStatus {
    var temperatureCurrent = -1.0
        private set
    private var batteryTempTime = 0L
    fun setBatteryTemperature(temperature: Double) {
        batteryTempTime = System.currentTimeMillis()
        temperatureCurrent = temperature
    }

    /**
     * Get real-time temperature.
     *
     * Reads through `dumpsys battery`, which is available in every tier (root, Shizuku and
     * non-root), so this no longer requires root. The root probe result is kept only as a
     * hint that the shell itself is usable; [PrivilegeManager.isPrivileged] covers the
     * shell-capable Shizuku tier as well.
     */
    fun updateBatteryTemperature(): Double {
        // throttle updates to >5 seconds
        val shellUsable = PrivilegeManager.isPrivileged || lastCheckResult
        if (shellUsable && System.currentTimeMillis() - 5000 >= batteryTempTime) {
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