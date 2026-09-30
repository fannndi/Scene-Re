package com.omarea.util.battery

import android.content.Context
import android.os.BatteryManager
import com.omarea.data.GlobalStatus
import com.omarea.data.SpfConfig
import com.omarea.util.measure.MeasureLog
import com.omarea.util.measure.SysReader
import kotlin.math.abs

/**
 * Central battery current sampler.
 *
 * Canonical convention: **positive = charging, negative = discharging**, in
 * display mA. The magnitude scale comes from `GLOBAL_SPF_CURRENT_NOW_UNIT`
 * (sign ignored) so existing preferences keep working; the *sign* is
 * calibrated against the charging status, because community kernels report
 * `current_now` with different polarity than stock MIUI.
 *
 * Sources: fuel-gauge average (`BATTERY_PROPERTY_CURRENT_AVERAGE` or
 * `bms/current_avg`) for the stable value, rolling median of `current_now`
 * (window 15) for the instantaneous one - a single raw sample swings
 * hundreds of mA on this platform (device-verified).
 *
 * Responsibility: read + normalise current. Non-goals: storage, UI.
 */
object BatterySampler {
    data class Reading(
        /** Rolling median, canonical sign, display mA. */
        val currentMa: Int,
        /** Fuel-gauge average, canonical sign, display mA (null when unsupported). */
        val averageMa: Int?,
        /** Raw CURRENT_NOW in µA (kernel sign), for logs. */
        val rawUa: Long,
        val source: String,
        val valid: Boolean
    )

    private const val MAX_PLAUSIBLE_UA = 20_000_000L
    private const val WINDOW = 15
    private const val SYSFS_AVG_TTL_MS = 30_000L
    private const val AVG_SYSFS = "/sys/class/power_supply/bms/current_avg"

    private val window = ArrayDeque<Long>()

    /** null = not determined yet, true = raw positive means charging. */
    private var chargingPositive: Boolean? = null

    private var sysfsAvgAvailable = true
    private var sysfsAvgMisses = 0
    private var sysfsAvgValue: Long? = null
    private var sysfsAvgAt = 0L

    /**
     * One sample; also updates [GlobalStatus.batteryCurrentNow] so every UI
     * receives the same smoothed, correctly-signed value.
     */
    @Synchronized
    fun sample(context: Context): Reading {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val rawNow = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val rawValid = isPlausible(rawNow)

        if (rawValid) {
            window.addLast(canonical(rawNow))
            while (window.size > WINDOW) window.removeFirst()
        }
        val medianRaw = CurrentMath.median(window)

        var averageRaw: Long? = null
        var avgSource = "none"
        val propertyAvg = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        if (isPlausible(propertyAvg)) {
            averageRaw = canonical(propertyAvg)
            avgSource = "current_average"
        } else if (sysfsAvgAvailable) {
            // The fuel gauge updates slowly (10s+); read it at most every 30s,
            // direct read first with one shell fallback (bms nodes are not
            // readable from the app domain on this ROM).
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - sysfsAvgAt > SYSFS_AVG_TTL_MS) {
                sysfsAvgAt = now
                sysfsAvgValue = SysReader.readFirst(AVG_SYSFS)?.toLongOrNull()?.takeIf { isPlausible(it) }
                if (sysfsAvgValue == null) {
                    sysfsAvgMisses += 1
                    if (sysfsAvgMisses >= 3) sysfsAvgAvailable = false
                }
            }
            if (sysfsAvgValue != null) {
                averageRaw = canonical(sysfsAvgValue!!)
                avgSource = "bms/current_avg"
            }
        }

        val unit = context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
            .getInt(SpfConfig.GLOBAL_SPF_CURRENT_NOW_UNIT, SpfConfig.GLOBAL_SPF_CURRENT_NOW_UNIT_DEFAULT)
        val scale = abs(unit).coerceAtLeast(1)

        val valid = medianRaw != null
        val reading = Reading(
            currentMa = ((medianRaw ?: 0L) / scale).toInt(),
            averageMa = averageRaw?.let { (it / scale).toInt() },
            rawUa = if (rawValid) rawNow else 0L,
            source = buildString {
                append(if (medianRaw != null) "median(current_now)" else "no_median")
                if (averageRaw != null) append("+").append(avgSource)
            },
            valid = valid
        )
        if (valid) {
            GlobalStatus.batteryCurrentNow = reading.currentMa.toLong()
        }
        return reading
    }

    /** Convenience: sample + one MeasureLog line for each current parameter. */
    @Synchronized
    fun sampleAndLog(context: Context): Reading {
        val reading = sample(context)
        MeasureLog.sample(
            "battery.current.raw", reading.rawUa.takeIf { it != 0L }, "uA", "current_now",
            valid = reading.valid
        )
        MeasureLog.sample(
            "battery.current.median", reading.currentMa, "mA", "median(15)",
            valid = reading.valid
        )
        MeasureLog.sample(
            "battery.current.avg", reading.averageMa, "mA", "fuel_gauge",
            valid = reading.averageMa != null
        )
        return reading
    }

    private fun isPlausible(value: Long): Boolean =
        value != Long.MIN_VALUE && value != Long.MAX_VALUE && abs(value) <= MAX_PLAUSIBLE_UA && value != 0L

    private fun canonical(raw: Long): Long {
        chargingPositive = CurrentMath.calibrate(
            raw,
            isCharging = GlobalStatus.batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING,
            isDischarging = GlobalStatus.batteryStatus == BatteryManager.BATTERY_STATUS_DISCHARGING,
            current = chargingPositive
        )
        return CurrentMath.canonical(raw, chargingPositive)
    }
}
