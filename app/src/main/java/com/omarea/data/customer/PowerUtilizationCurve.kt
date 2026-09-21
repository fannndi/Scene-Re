package com.omarea.data.customer

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import com.omarea.data.EventType
import com.omarea.data.GlobalStatus
import com.omarea.data.IEventReceiver
import com.omarea.library.basic.ScreenState
import com.omarea.model.BatteryStatus
import com.omarea.scene_mode.ModeSwitcher
import com.omarea.store.BatteryHistoryStore
import com.omarea.store.SpfConfig
import java.util.*

class PowerUtilizationCurve(context: Context) : IEventReceiver {
    private val storage = BatteryHistoryStore(context)
    private val screenState = ScreenState(context)
    private var timer: Timer? = null
    private var batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private var globalSPF = context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
    companion object {
        // sampling interval (ms)
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

    // battery level before charging
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
                // cancelUpdate()
            }
            EventType.POWER_DISCONNECTED -> {
                // if the battery is nearly full or the charged amount exceeds 40, clear records and restart stats
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
        // current
        GlobalStatus.batteryCurrentNow = (
                batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) /
                        globalSPF.getInt(SpfConfig.GLOBAL_SPF_CURRENT_NOW_UNIT, SpfConfig.GLOBAL_SPF_CURRENT_NOW_UNIT_DEFAULT)
                )

        // battery level
        GlobalStatus.batteryCapacity = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // status
            val batteryStatus = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            if (batteryStatus != BatteryManager.BATTERY_STATUS_UNKNOWN) {
                GlobalStatus.batteryStatus = batteryStatus;
            }
        }

        GlobalStatus.updateBatteryTemperature() // trigger temperature data update
    }

    private fun saveLog() {
        if(GlobalStatus.batteryCapacity < 1 || GlobalStatus.batteryStatus == BatteryManager.BATTERY_STATUS_UNKNOWN) {
            updateBatteryStatus()
        } else {
            // current
            GlobalStatus.batteryCurrentNow = (
                batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) /
                globalSPF.getInt(SpfConfig.GLOBAL_SPF_CURRENT_NOW_UNIT, SpfConfig.GLOBAL_SPF_CURRENT_NOW_UNIT_DEFAULT)
            )
            // batteryManager.getIntProperty(BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE)
        }

        // skip power stats within 5 minutes after boot to avoid inaccurate data while system services are busy
        // if (SystemClock.elapsedRealtime() > 300000L) {
            val status = BatteryStatus().apply {
                time = System.currentTimeMillis()
                temperature = GlobalStatus.temperatureCurrent
                status = GlobalStatus.batteryStatus
                io = GlobalStatus.batteryCurrentNow.toInt()
                screenOn = screenState.isScreenOn()
                capacity = GlobalStatus.batteryCapacity
            }
            status.packageName = ModeSwitcher.getCurrentPowermodeApp()
            status.mode = ModeSwitcher.getCurrentPowerMode()
            storage.insertHistory(status)
        // }
    }

    private fun cancelUpdate() {
        timer?.run {
            cancel()
            timer = null
        }
    }
}