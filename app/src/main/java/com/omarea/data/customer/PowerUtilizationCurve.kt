package com.omarea.data.customer

import android.content.Context
import android.os.BatteryManager
import android.os.SystemClock
import com.omarea.data.EventType
import com.omarea.data.GlobalStatus
import com.omarea.data.IEventReceiver
import com.omarea.util.ScreenState
import com.omarea.data.BatteryStatus
import com.omarea.runtime.ModeSwitcher
import com.omarea.runtime.TrueOff
import com.omarea.data.BatteryHistoryStore
import com.omarea.util.battery.BatterySampler
import com.omarea.util.measure.MeasureLog
import java.util.*

/**
 * Usage (discharge) curve sampler.
 *
 * Responsibility: every 3 s while the screen is on, store one smoothed current
 * sample (median current, canonical sign, real dt) plus temperature/mode/package
 * so per-app usage can be compared accurately.
 * Non-goals: charge control and UI.
 */
class PowerUtilizationCurve(context: Context) : IEventReceiver {
    // NB: applicationContext can be null during Application.attachBaseContext;
    // the passed Application context is safe to keep.
    private val appContext: Context = context
    private val storage = BatteryHistoryStore(context)
    private val screenState = ScreenState(context)
    private var timer: Timer? = null
    private var batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    /** Wall elapsed of the previous stored sample (real dt in the DB). */
    private var lastSampleAt = 0L

    companion object {
        // 采样间隔（毫秒）
        public val SAMPLING_INTERVAL: Long = 3000
    }

    override fun eventFilter(eventType: EventType): Boolean {
        return when (eventType) {
            EventType.SCREEN_ON,
            EventType.SCREEN_OFF,
            EventType.POWER_CONNECTED,
            EventType.POWER_DISCONNECTED,
            EventType.BATTERY_CHANGED -> {
                true
            }
            else -> false
        }
    }

    // 充电前的电量
    private var capacityBeforeRecharge = -1
    override fun onReceive(eventType: EventType, data: HashMap<String, Any>?) {
        when (eventType) {
            EventType.SCREEN_ON -> {
                startUpdate()
            }
            EventType.SCREEN_OFF -> {
                cancelUpdate()
                saveLog()
            }
            EventType.POWER_CONNECTED -> {
                capacityBeforeRecharge = GlobalStatus.batteryCapacity
            }
            EventType.POWER_DISCONNECTED -> {
                // 如果电量已经接近充满，或者本次充入电量超过40，清空记录重新开始统计
                if ((GlobalStatus.batteryCapacity > 85 && GlobalStatus.batteryCapacity - capacityBeforeRecharge > 1) ||
                    GlobalStatus.batteryCapacity - capacityBeforeRecharge > 40) {
                    storage.clearData()
                }
                startUpdate()
            }
            EventType.BATTERY_CHANGED -> {
                if (GlobalStatus.batteryStatus != BatteryManager.BATTERY_STATUS_CHARGING) {
                    capacityBeforeRecharge = GlobalStatus.batteryCapacity
                }
                startUpdate()
            }
            else -> {
            }
        }
    }

    override val isAsync: Boolean
        get() = true

    override fun onSubscribe() {
        startUpdate()
    }

    override fun onUnsubscribe() {

    }

    private fun startUpdate() {
        // TRUE OFF: usage-curve sampling timers stop (reads are allowed, but
        // the timers belong to the actuator services that must go quiet).
        if (!TrueOff.allowsWrite(appContext)) {
            cancelUpdate()
            return
        }
        if (screenState.isScreenOn()) {
            if (timer == null) {
                timer = Timer().apply {
                    scheduleAtFixedRate(object : TimerTask() {
                        override fun run() {
                            saveLog()
                        }
                    }, 0, SAMPLING_INTERVAL)
                }
            }
        }
    }

    private fun updateBatteryStatus() {
        // 电量
        GlobalStatus.batteryCapacity = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            // 状态
            val batteryStatus = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            if (batteryStatus != BatteryManager.BATTERY_STATUS_UNKNOWN) {
                GlobalStatus.batteryStatus = batteryStatus;
            }
        }

        GlobalStatus.updateBatteryTemperature() // 触发温度数据更新
    }

    private fun saveLog() {
        try {
            if (GlobalStatus.batteryCapacity < 1 || GlobalStatus.batteryStatus == BatteryManager.BATTERY_STATUS_UNKNOWN) {
                updateBatteryStatus()
            }

            val now = SystemClock.elapsedRealtime()
            val dtMs = if (lastSampleAt > 0) (now - lastSampleAt).coerceIn(500L, 30_000L) else SAMPLING_INTERVAL
            lastSampleAt = now

            val reading = BatterySampler.sample(appContext)
            val temperature = GlobalStatus.updateBatteryTemperature()
            if (!reading.valid) {
                // Unknown current: do not store fake numbers.
                MeasureLog.sample("usage.current", "invalid", "mA", reading.source, false, "dt=$dtMs")
                return
            }

            MeasureLog.sample("usage.current", reading.currentMa, "mA", reading.source, true, "dt=$dtMs")
            MeasureLog.sample("usage.temperature", temperature, "°C", "battery")
            MeasureLog.sample("usage.capacity", GlobalStatus.batteryCapacity, "%", "GlobalStatus")

            val status = BatteryStatus().apply {
                time = System.currentTimeMillis()
                this.temperature = temperature
                this.status = GlobalStatus.batteryStatus
                io = reading.currentMa
                screenOn = screenState.isScreenOn()
                capacity = GlobalStatus.batteryCapacity
                this.dtMs = dtMs
            }
            status.packageName = ModeSwitcher.getCurrentPowermodeApp()
            status.mode = ModeSwitcher.getCurrentPowerMode()
            storage.insertHistory(status)
        } catch (ex: Exception) {
            // A sampler must never kill the process.
            MeasureLog.sample("usage.error", ex.message, source = "PowerUtilizationCurve", valid = false)
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
