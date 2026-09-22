package com.omarea.vtools.kernel

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.omarea.common.shell.KeepShellPublic

/**
 * Battery snapshot: framework values from the sticky ACTION_BATTERY_CHANGED intent, sysfs values
 * through the shell when the tier allows it.
 *
 * Everything except [KernelBatterySnapshot.deepSleepMillis] and the capacity values works without
 * root, which is why the Battery tab stays useful in Shizuku mode.
 */
data class KernelBatterySnapshot(
    val framework: BatteryFrameworkSnapshot,
    val designCapacityUah: Long?,
    val fullCapacityUah: Long?,
    val deepSleepMillis: Long?
)

/** Battery values that need no shell access at all; safe to refresh on a timer. */
data class BatteryFrameworkSnapshot(
    val levelPercent: Int?,
    val statusCode: Int,
    val healthCode: Int,
    val pluggedCode: Int,
    val technology: String,
    val temperatureTenths: Int?,
    val voltageMv: Int?,
    val currentUa: Long?
)

data class ChargingControls(
    val fastChargePath: String?,
    val fastChargeEnabled: Boolean,
    val bypassPath: String?,
    val bypassEnabled: Boolean
)

object KernelBattery {
    private const val BATTERY_DIR = "/sys/class/power_supply/battery"
    private const val CURRENT_NOW = "$BATTERY_DIR/current_now"
    private const val CHARGE_FULL = "$BATTERY_DIR/charge_full"
    private const val CHARGE_FULL_DESIGN = "$BATTERY_DIR/charge_full_design"
    private const val INPUT_SUSPEND = "$BATTERY_DIR/input_suspend"
    private const val CHARGE_DISABLE = "$BATTERY_DIR/charge_disable"
    private const val FORCE_FAST_CHARGE = "/sys/kernel/fast_charge/force_fast_charge"
    private const val THERMAL_SCONFIG = "/sys/class/thermal/thermal_message/sconfig"

    /** MIUI thermal profile ids, kept in the same order as RvKernel-Manager. */
    val THERMAL_PROFILE_IDS = listOf("0", "8", "10", "11", "12", "13", "14")

    private val DEEP_SLEEP_REGEX = Regex(
        "Time on battery:\\s*([\\ddhms ]+?)\\s*\\([^)]*\\)\\s*realtime,\\s*([\\ddhms ]+?)\\s*\\([^)]*\\)\\s*uptime"
    )

    fun loadSnapshot(context: Context): KernelBatterySnapshot {
        val framework = loadFrameworkSnapshot(context)
        val values = KernelShell.readMany(listOf(CURRENT_NOW, CHARGE_FULL, CHARGE_FULL_DESIGN))
        return KernelBatterySnapshot(
            framework = if (framework.currentUa == null) {
                framework.copy(currentUa = values[CURRENT_NOW].orEmpty().toLongOrNull())
            } else {
                framework
            },
            designCapacityUah = values[CHARGE_FULL_DESIGN].orEmpty().toLongOrNull(),
            fullCapacityUah = values[CHARGE_FULL].orEmpty().toLongOrNull(),
            deepSleepMillis = readDeepSleepMillis()
        )
    }

    /** Framework-only snapshot; cheap enough to poll while the Battery tab is visible. */
    fun loadFrameworkSnapshot(context: Context): BatteryFrameworkSnapshot {
        val intent = readBatteryIntent(context)
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val levelPercent = if (level >= 0 && scale > 0) level * 100 / scale else null
        val temperature = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE && it > 0 }
        return BatteryFrameworkSnapshot(
            levelPercent = levelPercent,
            statusCode = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1,
            healthCode = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1,
            pluggedCode = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1,
            technology = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY).orEmpty(),
            temperatureTenths = temperature,
            voltageMv = voltage,
            currentUa = readFrameworkCurrent(context)
        )
    }

    fun loadChargingControls(): ChargingControls {
        val fastChargePath = KernelShell.firstAvailable(listOf(FORCE_FAST_CHARGE))
        val bypassPath = KernelShell.firstAvailable(listOf(INPUT_SUSPEND, CHARGE_DISABLE))
        val values = KernelShell.readMany(listOfNotNull(fastChargePath, bypassPath))
        return ChargingControls(
            fastChargePath = fastChargePath,
            fastChargeEnabled = fastChargePath != null && values[fastChargePath] == "1",
            bypassPath = bypassPath,
            bypassEnabled = bypassPath != null && values[bypassPath] == "1"
        )
    }

    fun loadThermalProfile(): String? {
        val value = KernelShell.read(THERMAL_SCONFIG)
        return value.ifEmpty { null }
    }

    fun setThermalProfile(value: String): Boolean {
        if (value !in THERMAL_PROFILE_IDS) {
            return false
        }
        // The node is often mode 0444; make it writable first, then restore the read-only mode.
        KeepShellPublic.doCmdSync("chmod 664 $THERMAL_SCONFIG 2>/dev/null")
        val written = KernelShell.write(THERMAL_SCONFIG, value)
        KeepShellPublic.doCmdSync("chmod 444 $THERMAL_SCONFIG 2>/dev/null")
        return written
    }

    private fun readBatteryIntent(context: Context): Intent? {
        return try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (ex: Exception) {
            null
        }
    }

    private fun readFrameworkCurrent(context: Context): Long? {
        val value = try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        } catch (ex: Exception) {
            null
        }
        if (value == null || value == Long.MIN_VALUE || value == 0L) {
            return null
        }
        return value
    }

    /**
     * Deep sleep = realtime - uptime, as reported by `dumpsys batterystats` for the current battery
     * session. Returns null when the framework stats cannot be parsed, so the caller can fall back
     * to the device-wide SystemClock delta.
     */
    private fun readDeepSleepMillis(): Long? {
        val output = try {
            KeepShellPublic.doCmdSync("dumpsys batterystats 2>/dev/null | grep -m1 'Time on battery:'")
        } catch (ex: Exception) {
            return null
        }
        val match = DEEP_SLEEP_REGEX.find(output) ?: return null
        val realtime = parseDuration(match.groupValues[1])
        val uptime = parseDuration(match.groupValues[2])
        if (realtime <= 0L || uptime <= 0L || realtime < uptime) {
            return null
        }
        return realtime - uptime
    }

    /** Parses strings like `1d 2h 3m 4s 500ms` into milliseconds. */
    fun parseDuration(value: String): Long {
        var millis = 0L
        for (match in Regex("(\\d+)(ms|d|h|m|s)").findAll(value)) {
            val amount = match.groupValues[1].toLongOrNull() ?: continue
            millis += when (match.groupValues[2]) {
                "ms" -> amount
                "d" -> amount * 24L * 60 * 60 * 1000
                "h" -> amount * 60L * 60 * 1000
                "m" -> amount * 60L * 1000
                "s" -> amount * 1000
                else -> 0L
            }
        }
        return millis
    }
}
