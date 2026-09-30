package com.omarea.data.customer

import android.content.Context
import android.os.BatteryManager
import android.os.SystemClock
import com.omarea.data.EventType
import com.omarea.data.GlobalStatus
import com.omarea.data.IEventReceiver
import com.omarea.data.ChargeSpeedStore
import com.omarea.util.battery.BatterySampler
import com.omarea.util.measure.MeasureLog
import java.util.*
import kotlin.math.abs

/**
 * Charging speed curve sampler.
 *
 * Responsibility: while charging, store one smoothed current sample per second
 * (median current from [BatterySampler], real dt, per-plug session id, fresh
 * temperature) for the charge charts.
 * Non-goals: charge control (ChargeController) and UI.
 */
class ChargeCurve(context: Context) : IEventReceiver {
    private val appContext = context.applicationContext
    private val storage = ChargeSpeedStore(context)
    private var timer: Timer? = null

    /** Session id = plug-in time, so curves never mix two charging runs. */
    private var sessionId = System.currentTimeMillis()
    private var lastSampleAt = 0L

    override fun eventFilter(eventType: EventType): Boolean {
        return when (eventType) {
            EventType.POWER_CONNECTED,
            EventType.POWER_DISCONNECTED,
            EventType.BATTERY_CHANGED -> {
                true
            }
            else -> false
        }
    }

    override fun onReceive(eventType: EventType, data: HashMap<String, Any>?) {
        when (eventType) {
            EventType.POWER_CONNECTED -> {
                sessionId = System.currentTimeMillis()
                lastSampleAt = 0L
                val last = storage.lastCapacity()
                if (GlobalStatus.batteryCapacity != -1 && GlobalStatus.batteryCapacity != last) {
                    storage.clearAll()
                }
            }
            EventType.POWER_DISCONNECTED -> {
                cancelUpdate()
            }
            EventType.BATTERY_CHANGED -> {
                if (timer == null && GlobalStatus.batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING) {
                    startUpdate()
                }
            }
            else -> {
            }
        }
    }

    override val isAsync: Boolean
        get() = true

    override fun onSubscribe() {

    }

    override fun onUnsubscribe() {

    }

    private fun startUpdate() {
        if (timer == null) {
            timer = Timer().apply {
                schedule(object : TimerTask() {
                    override fun run() {
                        saveLog()
                    }
                }, 15000, 1000)
            }
        }
    }

    private fun saveLog() {
        if (GlobalStatus.batteryStatus != BatteryManager.BATTERY_STATUS_CHARGING) {
            cancelUpdate()
            return
        }
        val reading = BatterySampler.sample(appContext)
        val now = SystemClock.elapsedRealtime()
        val dtMs = if (lastSampleAt > 0) (now - lastSampleAt).coerceIn(200L, 10_000L) else 1000L
        lastSampleAt = now

        MeasureLog.sample("charge.current", reading.currentMa, "mA", reading.source, reading.valid)
        MeasureLog.sample("charge.current.avg", reading.averageMa, "mA", "fuel_gauge", reading.averageMa != null)

        if (abs(reading.currentMa) > 100) {
            val temperature = GlobalStatus.updateBatteryTemperature()
            storage.addHistory(
                reading.currentMa.toLong(),
                GlobalStatus.batteryCapacity,
                temperature,
                dtMs,
                sessionId
            )
            MeasureLog.sample("charge.temperature", temperature, "°C", "battery")
            MeasureLog.sample("charge.capacity", GlobalStatus.batteryCapacity, "%", "GlobalStatus")
        }
    }

    private fun cancelUpdate() {
        timer?.run {
            cancel()
            timer = null
        }
        lastSampleAt = 0L
    }
}
