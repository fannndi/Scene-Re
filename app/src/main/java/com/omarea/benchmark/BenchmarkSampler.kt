package com.omarea.benchmark

import android.content.Context
import com.omarea.common.shell.KeepShellPublic
import com.omarea.data.GlobalStatus
import com.omarea.util.CpuLoadUtils
import com.omarea.util.battery.BatterySampler
import com.omarea.util.fps.FpsSampler
import com.omarea.util.measure.SysReader
import com.omarea.util.measure.ThermalMath

/**
 * One-second benchmark sample.
 *
 * Responsibility: read all bench parameters in one batched, time-consistent
 * snapshot (direct reads first, single shell fallback) and map them into a
 * [BenchSample]. No sentinels: missing reads stay `null`.
 *
 * Non-goals: aggregation (BenchmarkMetrics) and run orchestration.
 */
class BenchmarkSampler(private val context: Context) {
    private val cpuLoad = CpuLoadUtils()
    private val fpsSampler = FpsSampler()
    private var zones: List<Triple<String, String, String>>? = null
    private var lastCapacity: Int? = null

    fun sample(scenario: BenchScenario, elapsedMs: Long, dtMs: Long, workUnits: Long): BenchSample {
        val values = SysReader.read(READ_PATHS + (zones()?.map { it.third } ?: emptyList()))

        fun value(path: String): String? = values[path]?.takeIf { it.isNotBlank() && it != "error" }
        fun long(path: String): Long? = value(path)?.toLongOrNull()
        fun celsius(path: String): Double? = ThermalMath.decodePlausible(value(path)?.toDoubleOrNull())

        val reading = BatterySampler.sample(context)
        val capacity = value("/sys/class/power_supply/battery/capacity")?.toIntOrNull()
            ?: lastCapacity
            ?: GlobalStatus.batteryCapacity.takeIf { it >= 0 }
        lastCapacity = capacity

        val batteryMv = (long("/sys/class/power_supply/bms/voltage_avg")
            ?: long("/sys/class/power_supply/battery/voltage_now"))?.let { (it / 1000).toInt() }
        val usbMv = long("/sys/class/power_supply/usb/voltage_now")?.let { (it / 1000).toInt() }
        val usbMa = long("/sys/class/power_supply/usb/input_current_now")?.let { (it / 1000).toInt() }

        val zoneValues = zones().mapNotNull { (_, type, path) ->
            val c = celsius(path) ?: return@mapNotNull null
            type to c
        }.toMap()

        fun maxZone(vararg prefixes: String): Double? = zoneValues.entries
            .filter { entry -> prefixes.any { entry.key.contains(it) } }
            .maxOfOrNull { it.value }

        val fpsSample = if (scenario.needsFps) runCatching { fpsSampler.sample() }.getOrNull() else null

        return BenchSample(
            elapsedMs = elapsedMs,
            dtMs = dtMs,
            scenario = scenario,
            batteryMv = batteryMv,
            batteryMa = reading.currentMa.takeIf { reading.valid },
            usbMv = usbMv,
            usbMa = usbMa,
            usbType = value("/sys/class/power_supply/usb/real_type"),
            batteryTempC = GlobalStatus.updateBatteryTemperature().takeIf { it > 5.0 && it < 120.0 },
            capacityPct = capacity,
            socTempC = maxZone("cpu-0-", "cpu-1-", "cpuss"),
            cpu0TempC = maxZone("cpu-0-"),
            gpussTempC = maxZone("gpuss"),
            ddrTempC = zoneValues.entries.firstOrNull { it.key.contains("ddr") }?.value,
            cpu0Khz = long("/sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq"),
            cpu0MinKhz = long("/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq"),
            cpu0MaxKhz = long("/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"),
            cpu6Khz = long("/sys/devices/system/cpu/cpufreq/policy6/scaling_cur_freq"),
            cpu6MinKhz = long("/sys/devices/system/cpu/cpufreq/policy6/scaling_min_freq"),
            cpu6MaxKhz = long("/sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq"),
            cpuLoadPct = cpuLoad.cpuLoadSum.takeIf { it >= 0 },
            gpuMhz = normalizeMhz(long("/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq")),
            gpuLoadPct = value("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")
                ?.replace("%", "")?.trim()?.split(" ")?.firstOrNull()?.toDoubleOrNull()
                ?.coerceIn(0.0, 100.0),
            fps = fpsSample?.fps,
            fpsFrames = fpsSample?.frames,
            fpsJank = fpsSample?.jankFrames,
            workUnits = workUnits
        )
    }

    /** Hz / kHz / MHz -> MHz. */
    private fun normalizeMhz(value: Long?): Long? = value?.let {
        when {
            it >= 100_000_000L -> it / 1_000_000L
            it >= 100_000L -> it / 1_000L
            else -> it
        }
    }

    /** Thermal zones (name, type, temp path), discovered once per sampler. */
    private fun zones(): List<Triple<String, String, String>> {
        zones?.let { return it }
        val command = "for z in /sys/class/thermal/thermal_zone*; do " +
            "if [ -f \$z/temp ]; then echo \"\$(basename \$z)|\$(cat \$z/type)|\$z/temp\"; fi; done"
        val out = KeepShellPublic.doCmdSync(command)
        val parsed = ArrayList<Triple<String, String, String>>()
        for (line in out.lines()) {
            val parts = line.trim().split("|")
            if (parts.size == 3) {
                parsed.add(Triple(parts[0], parts[1].lowercase(), parts[2]))
            }
        }
        zones = parsed
        return parsed
    }

    private companion object {
        val READ_PATHS = listOf(
            "/sys/class/power_supply/bms/voltage_avg",
            "/sys/class/power_supply/battery/voltage_now",
            "/sys/class/power_supply/battery/capacity",
            "/sys/class/power_supply/usb/voltage_now",
            "/sys/class/power_supply/usb/input_current_now",
            "/sys/class/power_supply/usb/real_type",
            "/sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq",
            "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq",
            "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
            "/sys/devices/system/cpu/cpufreq/policy6/scaling_cur_freq",
            "/sys/devices/system/cpu/cpufreq/policy6/scaling_min_freq",
            "/sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq",
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage"
        )
    }
}
