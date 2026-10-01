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
 * Non-goals: charge control (removed — charging is read-only by policy) and UI.
 */
class ChargeCurve(context: Context) : IEventReceiver {
    // NB: applicationContext can be null during Application.attachBaseContext;
    // the passed Application context is safe to keep.
    private val appContext: Context = context
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
        try {
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

            // Input-side power (read-only): Vusb x Iusb, plus battery power —
            // same numbers the benchmark uses, for cross-run comparisons.
            val usb = com.omarea.util.measure.SysReader.read(
                "/sys/class/power_supply/usb/voltage_now",
                "/sys/class/power_supply/usb/input_current_now",
                "/sys/class/power_supply/bms/voltage_avg"
            )
            val usbMv = usb["/sys/class/power_supply/usb/voltage_now"]?.toLongOrNull()?.div(1000)
            val usbMa = usb["/sys/class/power_supply/usb/input_current_now"]?.toLongOrNull()?.div(1000)
            val battMv = usb["/sys/class/power_supply/bms/voltage_avg"]?.toLongOrNull()?.div(1000)
            fun f1(value: Double?): String? = value?.let { String.format(Locale.US, "%.1f", it) }
            MeasureLog.sample("charge.input.voltage", usbMv, "mV", "usb/voltage_now", usbMv != null)
            MeasureLog.sample("charge.input.current", usbMa, "mA", "usb/input_current_now", usbMa != null)
            MeasureLog.sample(
                "charge.input.power",
                f1(if (usbMv != null && usbMa != null) usbMv.toDouble() * usbMa.toDouble() / 1000.0 else null),
                "mW", "computed(v*i)", usbMv != null && usbMa != null
            )
            MeasureLog.sample(
                "charge.battery.power",
                f1(if (battMv != null) battMv.toDouble() * reading.currentMa / 1000.0 else null),
                "mW", "computed(bms/voltage_avg*i)", battMv != null && reading.valid
            )

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
        } catch (ex: Exception) {
            MeasureLog.sample("charge.error", ex.message, source = "ChargeCurve", valid = false)
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
