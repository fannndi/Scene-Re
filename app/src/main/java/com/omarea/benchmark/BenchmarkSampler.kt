package com.omarea.benchmark

import android.content.Context
import com.omarea.common.shell.KeepShellPublic
import com.omarea.data.GlobalStatus
import com.omarea.util.CpuLoadUtils
import com.omarea.util.battery.BatterySampler
import com.omarea.util.fps.FpsSampler
import com.omarea.util.measure.MeasureLog
import com.omarea.util.measure.SubsampleMath
import com.omarea.util.measure.SysReader
import com.omarea.util.measure.ThermalMath
import java.util.Locale

/**
 * One-second benchmark sample.
 *
 * Responsibility: read all bench parameters in one batched, time-consistent
 * snapshot (direct reads first, single shell fallback) and map them into a
 * [BenchSample]. Fast-moving parameters (CPU/GPU frequency, load, battery
 * current) are read [SUBSAMPLES] times inside the tick — the median lands in
 * the main columns, min/max document the spread. Every sample is mirrored
 * into the per-parameter `MeasureLog` as `bench.*` rows. No sentinels:
 * missing reads stay `null`.
 *
 * Non-goals: aggregation (BenchmarkMetrics) and run orchestration.
 */
class BenchmarkSampler(private val context: Context) {
    private val cpuLoad = CpuLoadUtils()
    private val fpsSampler = FpsSampler()
    private var zones: List<Triple<String, String, String>>? = null
    private var lastCapacity: Int? = null

    fun sample(scenario: BenchScenario, elapsedMs: Long, dtMs: Long, workUnits: Long): BenchSample {
        val zoneList = zones()
        val zoneTypeByPath = zoneList.associate { it.third to it.second }
        val allPaths = READ_PATHS + zoneList.map { it.third }

        var first: Map<String, String> = emptyMap()
        val cpu0 = ArrayList<Long?>()
        val cpu6 = ArrayList<Long?>()
        val gpuMhz = ArrayList<Long?>()
        val gpuLoad = ArrayList<Double?>()
        val cpuLoadPct = ArrayList<Double?>()
        val batteryMa = ArrayList<Int?>()

        for (i in 0 until SUBSAMPLES) {
            if (i > 0) Thread.sleep(SUBSAMPLE_SPACING_MS)
            val values = SysReader.read(allPaths)
            if (i == 0) first = values

            cpu0.add(longOf(values, POLICY0_CUR))
            cpu6.add(longOf(values, POLICY6_CUR))
            gpuMhz.add(normalizeMhz(longOf(values, GPU_FREQ)))
            gpuLoad.add(pctOf(values[GPU_BUSY]))
            cpuLoadPct.add(cpuLoad.cpuLoadSum.takeIf { it >= 0 })
            batteryMa.add(
                BatterySampler.sample(context).let { reading ->
                    reading.currentMa.takeIf { reading.valid }
                }
            )
        }

        // Slow-moving fields: first sub-sample is enough (same snapshot).
        fun value(path: String): String? = first[path]?.takeIf { it.isNotBlank() && it != "error" }
        fun long(path: String): Long? = value(path)?.toLongOrNull()
        fun celsius(path: String): Double? = ThermalMath.decodePlausible(value(path)?.toDoubleOrNull())

        val capacity = value("/sys/class/power_supply/battery/capacity")?.toIntOrNull()
            ?: lastCapacity
            ?: GlobalStatus.batteryCapacity.takeIf { it >= 0 }
        lastCapacity = capacity

        val batteryMv = (long("/sys/class/power_supply/bms/voltage_avg")
            ?: long("/sys/class/power_supply/battery/voltage_now"))?.let { (it / 1000).toInt() }
        val usbMv = long("/sys/class/power_supply/usb/voltage_now")?.let { (it / 1000).toInt() }
        val usbMa = long("/sys/class/power_supply/usb/input_current_now")?.let { (it / 1000).toInt() }

        val zoneValues = first.entries.mapNotNull { (path, _) ->
            val type = zoneTypeByPath[path] ?: return@mapNotNull null
            val c = celsius(path) ?: return@mapNotNull null
            type to c
        }.toMap()

        fun maxZone(vararg prefixes: String): Double? = zoneValues.entries
            .filter { entry -> prefixes.any { entry.key.contains(it) } }
            .maxOfOrNull { it.value }

        val fpsSample = if (scenario.needsFps) runCatching { fpsSampler.sample() }.getOrNull() else null

        // Sub-sample spreads: median in the main column, min/max for debug.
        val sCpu0 = SubsampleMath.spreadLong(cpu0)
        val sCpu6 = SubsampleMath.spreadLong(cpu6)
        val sGpuMhz = SubsampleMath.spreadLong(gpuMhz)
        val sGpuLoad = SubsampleMath.spread(gpuLoad)
        val sCpuLoad = SubsampleMath.spread(cpuLoadPct)
        val sBattery = SubsampleMath.spread(batteryMa.map { it?.toDouble() })

        val sample = BenchSample(
            elapsedMs = elapsedMs,
            dtMs = dtMs,
            scenario = scenario,
            batteryMv = batteryMv,
            batteryMa = sBattery.median?.toInt(),
            usbMv = usbMv,
            usbMa = usbMa,
            usbType = value("/sys/class/power_supply/usb/real_type"),
            batteryTempC = GlobalStatus.updateBatteryTemperature().takeIf { it > 5.0 && it < 120.0 },
            capacityPct = capacity,
            socTempC = maxZone("cpu-0-", "cpu-1-", "cpuss"),
            cpu0TempC = maxZone("cpu-0-"),
            gpussTempC = maxZone("gpuss"),
            ddrTempC = zoneValues.entries.firstOrNull { it.key.contains("ddr") }?.value,
            cpu0Khz = sCpu0.median?.toLong(),
            cpu0MinKhz = long("/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq"),
            cpu0MaxKhz = long("/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"),
            cpu6Khz = sCpu6.median?.toLong(),
            cpu6MinKhz = long("/sys/devices/system/cpu/cpufreq/policy6/scaling_min_freq"),
            cpu6MaxKhz = long("/sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq"),
            cpuLoadPct = sCpuLoad.median,
            gpuMhz = sGpuMhz.median?.toLong(),
            gpuLoadPct = sGpuLoad.median,
            fps = fpsSample?.fps,
            fpsFrames = fpsSample?.frames,
            fpsJank = fpsSample?.jankFrames,
            workUnits = workUnits,
            cpu0KhzMin = sCpu0.min?.toLong(),
            cpu0KhzMax = sCpu0.max?.toLong(),
            cpu6KhzMin = sCpu6.min?.toLong(),
            cpu6KhzMax = sCpu6.max?.toLong(),
            cpuLoadMin = sCpuLoad.min,
            cpuLoadMax = sCpuLoad.max,
            gpuMhzMin = sGpuMhz.min?.toLong(),
            gpuMhzMax = sGpuMhz.max?.toLong(),
            gpuLoadMin = sGpuLoad.min,
            gpuLoadMax = sGpuLoad.max,
            batteryMaMin = sBattery.min?.toInt(),
            batteryMaMax = sBattery.max?.toInt()
        )
        logToMeasureLog(scenario, sample)
        return sample
    }

    /** Mirror the sample into the unified per-parameter MeasureLog. */
    private fun logToMeasureLog(scenario: BenchScenario, sample: BenchSample) {
        if (!MeasureLog.enabled) return
        val src = "bench"
        val extra = scenario.id
        fun f1(value: Double?): String? = value?.let { String.format(Locale.US, "%.1f", it) }
        MeasureLog.sample("bench.battery.ma", sample.batteryMa, "mA", src, sample.batteryMa != null, extra)
        MeasureLog.sample("bench.battery.mw", f1(sample.batteryMw), "mW", src, sample.batteryMw != null, extra)
        MeasureLog.sample("bench.usb.mw", f1(sample.usbMw), "mW", src, sample.usbMw != null, extra)
        MeasureLog.sample("bench.soc.temp", f1(sample.socTempC), "°C", src, sample.socTempC != null, extra)
        MeasureLog.sample("bench.cpu0.khz", sample.cpu0Khz, "kHz", src, sample.cpu0Khz != null, extra)
        MeasureLog.sample("bench.cpu6.khz", sample.cpu6Khz, "kHz", src, sample.cpu6Khz != null, extra)
        MeasureLog.sample("bench.cpu.load", f1(sample.cpuLoadPct), "%", src, sample.cpuLoadPct != null, extra)
        MeasureLog.sample("bench.gpu.mhz", sample.gpuMhz, "MHz", src, sample.gpuMhz != null, extra)
        MeasureLog.sample("bench.gpu.load", f1(sample.gpuLoadPct), "%", src, sample.gpuLoadPct != null, extra)
        MeasureLog.sample("bench.fps", sample.fps?.toDouble(), "fps", src, sample.fps != null, extra)
        MeasureLog.sample("bench.capacity", sample.capacityPct, "%", src, sample.capacityPct != null, extra)
        MeasureLog.sample("bench.work.units", sample.workUnits, "units", src, true, extra)
    }

    private fun longOf(values: Map<String, String>, path: String): Long? =
        values[path]?.takeIf { it.isNotBlank() && it != "error" }?.toLongOrNull()

    private fun pctOf(raw: String?): Double? = raw
        ?.replace("%", "")?.trim()?.split(" ")?.firstOrNull()?.toDoubleOrNull()
        ?.coerceIn(0.0, 100.0)

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
        /** Fast-parameter readings per 1 Hz tick (spaced SUBSAMPLE_SPACING_MS). */
        const val SUBSAMPLES = 5
        const val SUBSAMPLE_SPACING_MS = 200L

        const val POLICY0_CUR = "/sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq"
        const val POLICY6_CUR = "/sys/devices/system/cpu/cpufreq/policy6/scaling_cur_freq"
        const val GPU_FREQ = "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq"
        const val GPU_BUSY = "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage"

        val READ_PATHS = listOf(
            "/sys/class/power_supply/bms/voltage_avg",
            "/sys/class/power_supply/battery/voltage_now",
            "/sys/class/power_supply/battery/capacity",
            "/sys/class/power_supply/usb/voltage_now",
            "/sys/class/power_supply/usb/input_current_now",
            "/sys/class/power_supply/usb/real_type",
            POLICY0_CUR,
            "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq",
            "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
            POLICY6_CUR,
            "/sys/devices/system/cpu/cpufreq/policy6/scaling_min_freq",
            "/sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq",
            GPU_FREQ,
            GPU_BUSY
        )
    }
}
