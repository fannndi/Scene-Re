package com.omarea.data.customer

import android.content.Context
import android.content.SharedPreferences
import android.os.BatteryManager
import android.util.Log
import android.widget.Toast
import com.omarea.Scene
import com.omarea.common.shared.FileWrite
import com.omarea.common.shell.KeepShellAsync
import com.omarea.data.EventType
import com.omarea.data.GlobalStatus
import com.omarea.data.IEventReceiver
import com.omarea.library.calculator.GetUpTime
import com.omarea.library.device.BatteryCapacity
import com.omarea.library.shell.BatteryUtils
import com.omarea.library.shell.PropsUtils
import com.omarea.store.SpfConfig
import java.util.*

class BatteryReceiver(private var service: Context, override val isAsync: Boolean = true) : IEventReceiver {
    override fun eventFilter(eventType: EventType): Boolean {
        return when (eventType) {
            EventType.BATTERY_CAPACITY_CHANGED, // battery level percentage changed
            EventType.BATTERY_CHANGED,              // this may fire too frequently and drain battery
            EventType.BATTERY_LOW,
            EventType.POWER_CONNECTED,
            EventType.POWER_DISCONNECTED,
            EventType.CHARGE_CONFIG_CHANGED -> true
            else -> false
        }
    }

    // whether charge protection is enabled
    private val bpAllowed: Boolean
        get() {
            return chargeConfig.getBoolean(SpfConfig.CHARGE_SPF_BP, false)
        }

    // charge protection battery level percentage
    private val bpLevel: Int
        get() {
            return chargeConfig.getInt(SpfConfig.CHARGE_SPF_BP_LEVEL, SpfConfig.CHARGE_SPF_BP_LEVEL_DEFAULT)
        }

    // whether charging
    private val onCharge: Boolean
        get() {
            return GlobalStatus.batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING
        }

    // whether battery protection should be active
    private val shouldBP: Boolean
        get() {
            return bpAllowed && (GlobalStatus.batteryCapacity >= bpLevel || (chargeDisabled && GlobalStatus.batteryCapacity > bpLevel - 20))
        }

    override fun onReceive(eventType: EventType, data: HashMap<String, Any>?) {
        if (GlobalStatus.batteryCapacity < 0) {
            return
        }

        try {
            // charge protection
            if (shouldBP != chargeDisabled) {
                if (chargeDisabled) {
                    // resume charging
                    resumeCharge()
                } else {
                    // stop charging
                    disableCharge()
                    return
                }
            }

            if (onCharge) {
                // night slow charging
                val isSleepTime = sleepChargeMode(GlobalStatus.batteryCapacity, if (bpAllowed) bpLevel else 100, qcLimit, eventType)

                // charge acceleration
                if (!isSleepTime && chargeConfig.getBoolean(SpfConfig.CHARGE_SPF_QC_BOOSTER, false)) {
                    autoChangeLimitValue(eventType)
                }
            }
        } catch (ex: Exception) {
        }
    }

    override fun onSubscribe() {

    }

    override fun onUnsubscribe() {

    }

    private var chargeDisabled: Boolean = PropsUtils.getProp("vtools.bp").equals("1")
    private var keepShellAsync: KeepShellAsync? = null

    private var chargeConfig: SharedPreferences

    // total battery capacity (mAh)
    private val batteryCapacity = BatteryCapacity().getBatteryCapacity(service)

    private var batteryUnits = BatteryUtils()
    private var ResumeCharge = "sh " + FileWrite.writePrivateShellFile("addin/resume_charge.sh", "addin/resume_charge.sh", service)
    private var DisableCharge = "sh " + FileWrite.writePrivateShellFile("addin/disable_charge.sh", "addin/disable_charge.sh", service)

    // wake-up time
    private val getUpTime: Int
        get() {
            return chargeConfig.getInt(SpfConfig.CHARGE_SPF_TIME_GET_UP, SpfConfig.CHARGE_SPF_TIME_GET_UP_DEFAULT)
        }

    // sleep time
    private val goToBedTime: Int
        get() {
            return chargeConfig.getInt(SpfConfig.CHARGE_SPF_TIME_SLEEP, SpfConfig.CHARGE_SPF_TIME_SLEEP_DEFAULT)
        }

    // current time
    private val currentTime: Int
        get() {
            val now = Calendar.getInstance()
            return now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        }

    private val qcLimit: Int
        get() {
            return chargeConfig.getInt(SpfConfig.CHARGE_SPF_QC_LIMIT, SpfConfig.CHARGE_SPF_QC_LIMIT_DEFAULT)
        }

    private var lowSpeedMedium = 1000 // charge speed limit when entering the slow phase
    private var lowSpeedHigh = 500 // charge speed limit when entering the slow phase
    private var lowSpeedExtreme = 100 // charge speed limit when entering the slow phase (charge speed control is imprecise; to avoid charge/discharge cycles caused by controller inaccuracy and device power fluctuation, the minimum is unlikely to be 0)

    private var lastLimitValue = -1

    // check whether it is night slow charging time
    private fun inSleepTime(): Boolean {
        // if night charge speed reduction is enabled
        if (chargeConfig.getBoolean(SpfConfig.CHARGE_SPF_NIGHT_MODE, false)) {
            val nowTimeValue = currentTime
            val getUp = getUpTime
            val sleep = goToBedTime

            // check whether it is night slow charging time
            return (getUp > sleep && (nowTimeValue in sleep..getUp)) ||
                    // normal schedule: sleep time is later than wake-up time, e.g. sleep at 23:00 and wake up at 7:00
                    (getUp < sleep && (nowTimeValue >= sleep || nowTimeValue <= getUp))
        }
        return false
    }

    /**
     * Calculate and apply a reasonable night charging speed
     * @param currentCapacityRatio current battery level percentage (0~100)
     * @param targetRatio target charge percentage
     * @param qcLimit charge speed limit
     */
    private fun sleepChargeMode(currentCapacityRatio: Int, targetRatio: Int, qcLimit: Int, eventType: EventType): Boolean {
        // do not use slow charging below 20% battery level
        if (currentCapacityRatio < 20) {
            return false
        }

        val inSleepTime = inSleepTime()
        // if night charge speed reduction is enabled and it is night slow charging time
        if (inSleepTime) {
            val getUp = getUpTime
            if (currentCapacityRatio >= targetRatio) {
                // if battery level is above the charge protection threshold, limit to 50mA
                if (lastLimitValue != lowSpeedExtreme) { // avoid repeating the same operation
                    lastLimitValue = lowSpeedExtreme
                    batteryUnits.setChargeInputLimit(lastLimitValue, service, eventType == EventType.BATTERY_CAPACITY_CHANGED)
                }
            } else {
                // calculate how much more charge is needed (mAh)
                val target = (targetRatio - currentCapacityRatio) / 100F * batteryCapacity
                // remaining time until wake-up (hours)
                val timeRemaining = GetUpTime(getUp).minutes / 60F

                // reasonable charge speed = remaining charge needed (mAh) / timeRemaining
                var limitValue = (target / timeRemaining).toInt()
                if (limitValue < lowSpeedExtreme) {
                    limitValue = lowSpeedExtreme
                } else if (limitValue > qcLimit) {
                    limitValue = qcLimit
                }

                if (lastLimitValue != limitValue) { // avoid repeating the same operation
                    lastLimitValue = limitValue
                    batteryUnits.setChargeInputLimit(limitValue, service, eventType == EventType.BATTERY_CAPACITY_CHANGED)
                }
            }
            return true
        }
        return false
    }

    init {
        if (keepShellAsync == null) {
            keepShellAsync = KeepShellAsync(service)
        }
        chargeConfig = service.getSharedPreferences(SpfConfig.CHARGE_SPF, Context.MODE_PRIVATE)
    }

    internal fun onDestroy() {
        this.resumeCharge()
        keepShellAsync?.tryExit()
        keepShellAsync = null
    }

    private fun disableCharge() {
        Scene.toast("Charging protection has paused charging.", Toast.LENGTH_SHORT)
        keepShellAsync?.doCmd(DisableCharge)
        chargeDisabled = true
    }

    private fun resumeCharge() {
        Scene.toast("Charging protection has resumed charging.", Toast.LENGTH_SHORT)
        keepShellAsync!!.doCmd(ResumeCharge)
        chargeDisabled = false
    }

    private var lastSetChargeLimit = 0L

    // automatically adjust the speed limit based on battery level and settings
    private fun autoChangeLimitValue(eventType: EventType) {
        // whether dynamic speed adjustment is enabled
        val allowDynamicSpeed = chargeConfig.getBoolean(SpfConfig.CHARGE_SPF_NIGHT_MODE, false)

        // if dynamic speed adjustment is enabled and the battery is almost full
        if (allowDynamicSpeed && GlobalStatus.batteryCapacity > 80) {
            setChargerLimitToValue(when {
                GlobalStatus.batteryCapacity > 90 -> lowSpeedExtreme
                GlobalStatus.batteryCapacity > 85 -> lowSpeedHigh
                else -> lowSpeedMedium
            }, eventType, true) // when nearly full, limit charge speed to 50mA to protect the battery!
        }

        // or only charge acceleration is enabled
        else if (chargeConfig.getBoolean(SpfConfig.CHARGE_SPF_QC_BOOSTER, false)) {
            setChargerLimitToValue(qcLimit, eventType, false) // when nearly full, limit charge speed to 50mA to protect the battery!
        }
    }

    private var governorTimer: Timer? = null
    private fun startGovernorTimer() {
        if (governorTimer == null) {
            Log.d("@Scene", "Start ForceQuickChargeTimer")
            governorTimer = Timer().apply {
                schedule(object : TimerTask() {
                    override fun run() {
                        governorRun()
                    }

                }, 0, 1000)
            }
        }
    }

    private fun stopGovernorTimer() {
        if (governorTimer != null) {
            Log.d("@Scene", "Stop ForceQuickChargeTimer")
            governorTimer?.cancel()
            governorTimer?.purge()
            governorTimer = null
        }
    }

    private fun governorRun() {
        if (chargeConfig.getInt(SpfConfig.CHARGE_SPF_EXEC_MODE, SpfConfig.CHARGE_SPF_EXEC_MODE_DEFAULT) == SpfConfig.CHARGE_SPF_EXEC_MODE_SPEED_FORCE) {
            if (inSleepTime() || chargeDisabled) {
                stopGovernorTimer()
            } else {
                autoChangeLimitValue(EventType.TIMER)
            }
        } else {
            stopGovernorTimer()
        }
    }

    // limit to the specified value
    private fun setChargerLimitToValue(speedMa: Int, eventType: EventType, protectedMode: Boolean) {
        val execMode = chargeConfig.getInt(SpfConfig.CHARGE_SPF_EXEC_MODE, SpfConfig.CHARGE_SPF_EXEC_MODE_DEFAULT)
        try {
            var forceRun = false
            val required = if (eventType == EventType.CHARGE_CONFIG_CHANGED) {
                true
            } else {
                when (execMode) {
                    // when the goal is to reduce charge speed, there is no need to adjust frequently; only on battery level change or charger plug/unplug
                    SpfConfig.CHARGE_SPF_EXEC_MODE_SPEED_DOWN -> {
                        if (eventType != EventType.BATTERY_CHANGED) {
                            Log.d("@Scene", "CHARGE_SPF_EXEC_MODE_SPEED_DOWN > " + eventType.name)
                        }
                        forceRun = true
                        eventType == EventType.BATTERY_CAPACITY_CHANGED || eventType == EventType.POWER_CONNECTED || eventType == EventType.POWER_DISCONNECTED
                    }
                    // for forced charge acceleration, adjust as frequently as possible with a timed task (unless already in dynamic speed protection)
                    SpfConfig.CHARGE_SPF_EXEC_MODE_SPEED_FORCE -> {
                        if (!protectedMode) {
                            if (eventType != EventType.TIMER) {
                                startGovernorTimer()
                            } else {
                                Log.d("@Scene", "Exec ForceQuickChargeTimer")
                            }
                            true
                        } else {
                            stopGovernorTimer()
                            // even in charge protection, limit the execution frequency
                            !(protectedMode && (System.currentTimeMillis() - lastSetChargeLimit < 5000))
                        }
                    }
                    // for regular acceleration, run on every battery state change (unless already in dynamic speed protection)
                    SpfConfig.CHARGE_SPF_EXEC_MODE_SPEED_UP -> {
                        // even in charge protection, limit the execution frequency
                        !(protectedMode && (System.currentTimeMillis() - lastSetChargeLimit < 5000))
                    }
                    else -> false
                }
            }

            if (required) {
                lastSetChargeLimit = System.currentTimeMillis()
                lastLimitValue = speedMa
                batteryUnits.setChargeInputLimit(lastLimitValue, service, forceRun)
            }
        } catch (ex: Exception) {
        }
    }
}
