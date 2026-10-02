package com.omarea.runtime

import android.os.BatteryManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure formatting contract for the status notification (Notification v2).
 */
class NotificationFormatTest {

    @Test
    fun `battery state classification`() {
        assertEquals(
            NotificationFormat.BatteryState.CHARGING,
            NotificationFormat.batteryState(BatteryManager.BATTERY_STATUS_CHARGING)
        )
        assertEquals(
            NotificationFormat.BatteryState.DISCHARGING,
            NotificationFormat.batteryState(BatteryManager.BATTERY_STATUS_DISCHARGING)
        )
        assertEquals(
            NotificationFormat.BatteryState.FULL,
            NotificationFormat.batteryState(BatteryManager.BATTERY_STATUS_FULL)
        )
        assertEquals(
            NotificationFormat.BatteryState.NOT_CHARGING,
            NotificationFormat.batteryState(BatteryManager.BATTERY_STATUS_NOT_CHARGING)
        )
        assertEquals(
            NotificationFormat.BatteryState.UNKNOWN,
            NotificationFormat.batteryState(BatteryManager.BATTERY_STATUS_UNKNOWN)
        )
    }

    @Test
    fun `watts formatting`() {
        assertEquals("4.2W", NotificationFormat.formatWatts(4.2, 1000))
        assertEquals("12W", NotificationFormat.formatWatts(12.34, 1000))
        assertEquals("0.5W", NotificationFormat.formatWatts(4.2, 123))
        assertEquals("", NotificationFormat.formatWatts(0.0, 1000))
        assertEquals("", NotificationFormat.formatWatts(4.2, 0))
    }

    @Test
    fun `current line falls back gracefully`() {
        assertEquals("1033mA · 4.3W", NotificationFormat.currentLine(1033, true, "4.3W"))
        assertEquals("1033mA", NotificationFormat.currentLine(1033, true, ""))
        assertEquals("4.3W", NotificationFormat.currentLine(0, false, "4.3W"))
        assertEquals("", NotificationFormat.currentLine(0, false, ""))
    }

    @Test
    fun `capacity line falls back gracefully`() {
        assertEquals("92% · 34.2°C", NotificationFormat.capacityLine(92, 34.2))
        assertEquals("34.2°C", NotificationFormat.capacityLine(-1, 34.2))
        assertEquals("92%", NotificationFormat.capacityLine(92, 0.0))
        assertEquals("", NotificationFormat.capacityLine(-1, 0.0))
    }
}
