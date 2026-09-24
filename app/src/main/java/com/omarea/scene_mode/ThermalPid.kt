package com.omarea.scene_mode

import android.content.Context
import com.omarea.common.shell.KeepShellPublic
import com.omarea.store.SpfConfig
import com.omarea.utils.SceneLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Thermal PID loop driving the generic cooling devices.
 *
 * Ported in spirit from AZenith's thermalcore (Apache-2.0): instead of writing
 * vendor frequency nodes, it only touches /sys/class/thermal/cooling_device*
 * cur_state, which is generic across kernels. The previously advertised "AI"
 * was a plain PID state machine there as well, so this keeps the same model and
 * drops the misleading wording.
 *
 * Requires no Magisk and no vendor specific node. Started by the accessibility
 * service when enabled in Profile options.
 */
object ThermalPid {
    private const val TICK_MS = 1000L
    private const val EWMA_ALPHA = 0.3
    private const val KP = 0.008
    private const val KI = 0.0005
    private const val KD = 0.02
    private const val INTEGRAL_LIMIT = 100.0

    private val acceptPatterns = listOf("cpu", "gpu", "devfreq", "vcore", "thermal-cpufreq")
    private val rejectPatterns = listOf("backlight", "cam", "tx-pwr", "shutdown", "sysrst", "mdoff")

    private data class Device(val path: String, val type: String, val maxState: Int, val initialState: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var job: Job? = null

    private var devices: List<Device> = emptyList()
    private var smoothTemp = -1.0
    private var smoothIntensity = -1.0
    private var integral = 0.0
    private var lastError = 0.0

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
            .getBoolean(SpfConfig.GLOBAL_SPF_THERMAL_PID, false)

    /** Start or stop the loop to match the current preference. */
    fun sync(context: Context) {
        if (isEnabled(context)) {
            start()
        } else {
            stop()
        }
    }

    @Synchronized
    fun start() {
        if (job != null) {
            return
        }
        devices = enumerateDevices()
        if (devices.isEmpty()) {
            SceneLog.w("ThermalPid", "no usable cooling devices found; not starting")
            return
        }
        smoothTemp = -1.0
        smoothIntensity = -1.0
        integral = 0.0
        lastError = 0.0
        SceneLog.i("ThermalPid", "started with ${devices.size} cooling devices")

        job = scope.launch {
            while (isActive) {
                try {
                    tick()
                } catch (ex: Exception) {
                    SceneLog.e("ThermalPid", "tick failed", ex)
                }
                delay(TICK_MS)
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        if (devices.isNotEmpty()) {
            val command = StringBuilder()
            devices.forEach { device ->
                command.append("echo ")
                    .append(device.initialState)
                    .append(" > ").append(device.path).append("/cur_state 2> /dev/null\n")
            }
            if (command.isNotEmpty()) {
                KeepShellPublic.doCmdSync(command.toString())
            }
        }
    }

    private fun tick() {
        val rawTemp = readBatteryTempTenths()
        if (rawTemp < 0) {
            return
        }
        val temp = if (smoothTemp < 0) rawTemp / 10.0 else smoothTemp * (1 - EWMA_ALPHA) + (rawTemp / 10.0) * EWMA_ALPHA
        smoothTemp = temp

        val target = targetFor(temp)
        val error = (temp - target) * 10.0
        integral = (integral + error).coerceIn(-INTEGRAL_LIMIT, INTEGRAL_LIMIT)
        val derivative = error - lastError
        lastError = error

        val raw = (KP * error + KI * integral + KD * derivative).coerceIn(0.0, 1.0)
        val intensity =
            if (smoothIntensity < 0) raw else smoothIntensity * (1 - EWMA_ALPHA) + raw * EWMA_ALPHA
        smoothIntensity = intensity

        if (intensity <= 0.001 && devices.all { it.initialState == 0 }) {
            return
        }

        val command = StringBuilder()
        devices.forEach { device ->
            val state = (intensity * device.maxState).roundToInt().coerceIn(0, device.maxState)
            command.append("echo ")
                .append(state)
                .append(" > ").append(device.path).append("/cur_state 2> /dev/null\n")
        }
        KeepShellPublic.doCmdSync(command.toString())
    }

    private fun targetFor(tempC: Double): Double {
        if (tempC >= 48.0) {
            return 36.0
        }
        return when (ModeSwitcher.getCurrentPowerMode()) {
            ModeSwitcher.POWERSAVE -> 38.0
            ModeSwitcher.PERFORMANCE, ModeSwitcher.FAST -> 44.0
            else -> 40.0
        }
    }

    private fun readBatteryTempTenths(): Int {
        val raw = KeepShellPublic.doCmdSync("cat /sys/class/power_supply/battery/temp 2> /dev/null").trim()
        return raw.toIntOrNull() ?: -1
    }

    private fun enumerateDevices(): List<Device> {
        val listing = KeepShellPublic.doCmdSync(
            "for d in /sys/class/thermal/cooling_device*; do " +
                "echo \"\$d \$(cat \$d/type 2>/dev/null) \$(cat \$d/max_state 2>/dev/null) \$(cat \$d/cur_state 2>/dev/null)\"; " +
                "done"
        )
        return listing.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(" ")
            if (parts.size < 4) {
                return@mapNotNull null
            }
            val path = parts[0]
            val type = parts.subList(1, parts.size - 2).joinToString(" ")
            val maxState = parts[parts.size - 2].toIntOrNull() ?: return@mapNotNull null
            val initialState = parts.last().toIntOrNull() ?: 0
            if (maxState <= 0) {
                return@mapNotNull null
            }
            val lower = type.lowercase()
            if (acceptPatterns.none { lower.contains(it) } || rejectPatterns.any { lower.contains(it) }) {
                return@mapNotNull null
            }
            Device(path, type, maxState, initialState)
        }.toList()
    }
}
