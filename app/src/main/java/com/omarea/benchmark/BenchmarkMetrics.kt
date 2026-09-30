package com.omarea.benchmark

import kotlin.math.abs

/**
 * Benchmark aggregation (pure, JVM-testable).
 *
 * Responsibility: turn per-second [BenchSample]s into one honest per-scenario
 * summary: dt-weighted averages, integrals (mAh / mWh), capacity drain percent
 * (AnTuTu-style), frequency histograms and throughput-normalised energy.
 *
 * Non-goals: sampling and file output.
 */
object BenchmarkMetrics {

    data class Summary(
        val scenario: BenchScenario,
        val samples: Int,
        val durationMs: Long,
        val avgBatteryMa: Double?,
        val medianBatteryMa: Double?,
        val maxAbsBatteryMa: Double?,
        val avgUsbMa: Double?,
        /** Battery power (discharge) or USB input power (charger), mW. */
        val avgMw: Double?,
        val maxMw: Double?,
        /** Signed integral of battery current: negative = drained. */
        val mah: Double?,
        val mwh: Double?,
        /** |mAh| / design capacity * 100 (measured, not extrapolated). */
        val pctOfDesign: Double?,
        val pctPerHour: Double?,
        val avgBatteryTempC: Double?,
        val maxBatteryTempC: Double?,
        val deltaBatteryTempC: Double?,
        val maxSocTempC: Double?,
        val avgCpu0Khz: Double?,
        val avgCpu6Khz: Double?,
        /** Frequency (kHz) -> seconds spent. */
        val freqHistogram0: Map<Long, Double>,
        val freqHistogram6: Map<Long, Double>,
        val avgCpuLoadPct: Double?,
        val avgGpuMhz: Double?,
        val avgGpuLoadPct: Double?,
        val fpsAvg: Double?,
        val fpsP95: Double?,
        val fpsMin: Double?,
        val jankPct: Double?,
        /** Cumulative work counter delta over the scenario. */
        val workUnits: Long,
        val workPerSec: Double?,
        val mwhPerKiloWork: Double?
    )

    fun summarize(
        samples: List<BenchSample>,
        mode: BenchMode,
        designCapacityMah: Double? = null
    ): Summary {
        val scenario = samples.firstOrNull()?.scenario ?: BenchScenario.IDLE
        val usable = samples.filter { it.dtMs > 0 }
        if (usable.isEmpty()) {
            return Summary(
                scenario, 0, 0, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, emptyMap(), emptyMap(), null, null, null, null, null, null, null,
                0L, null, null
            )
        }

        val durationMs = usable.sumOf { it.dtMs.coerceIn(100L, 15_000L) }
        val hours = durationMs / 3_600_000.0

        fun weighted(values: List<Pair<Double?, Long>>): Double? {
            var sum = 0.0
            var weight = 0.0
            for ((value, dt) in values) {
                if (value == null) continue
                val w = dt.coerceIn(100L, 15_000L).toDouble()
                sum += value * w
                weight += w
            }
            return if (weight > 0) sum / weight else null
        }

        fun maxOf(values: List<Double?>): Double? = values.filterNotNull().maxOrNull()

        val batteryMaValues = usable.map { it.batteryMa?.toDouble() to it.dtMs }
        val avgBatteryMa = weighted(batteryMaValues)
        val medianBatteryMa = usable.mapNotNull { it.batteryMa?.toDouble() }.sorted()
            .let { if (it.isEmpty()) null else it[it.size / 2] }
        val maxAbsBatteryMa = usable.mapNotNull { it.batteryMa?.toDouble() }.maxOfOrNull { abs(it) }

        val usbMaValues = usable.map { it.usbMa?.toDouble() to it.dtMs }
        val avgUsbMa = if (mode == BenchMode.CHARGER) weighted(usbMaValues) else null

        // Power magnitude: discharge power is a consumption, the sign of the
        // energy is carried by `mah` (negative = drained).
        val powerValues = (if (mode == BenchMode.CHARGER) {
            usable.map { it.usbMw to it.dtMs }
        } else {
            usable.map { it.batteryMw to it.dtMs }
        }).map { (value, dt) -> value?.let { abs(it) } to dt }
        val avgMw = weighted(powerValues)
        val maxMw = powerValues.mapNotNull { it.first }.maxOrNull()

        // Integrals: mAh = sum(mA * dt_ms) / 3.6e6 ; mWh = sum(mW * dt_ms) / 3.6e6
        val mah = batteryMaValues.fold(0.0) { acc, (value, dt) ->
            if (value == null) acc else acc + value * dt / 3_600_000.0
        }.takeIf { usable.any { s -> s.batteryMa != null } }
        val mwh = powerValues.fold(0.0) { acc, (value, dt) ->
            if (value == null) acc else acc + value * dt / 3_600_000.0
        }.takeIf { usable.any { s -> (if (mode == BenchMode.CHARGER) s.usbMw else s.batteryMw) != null } }

        val pctOfDesign = if (mah != null && designCapacityMah != null && designCapacityMah > 0) {
            mah / designCapacityMah * 100.0
        } else null
        val pctPerHour = if (mah != null && designCapacityMah != null && designCapacityMah > 0 && hours > 0) {
            mah / designCapacityMah * 100.0 / hours
        } else null

        val tempValues = usable.map { it.batteryTempC to it.dtMs }
        val avgBatteryTempC = weighted(tempValues)
        val maxBatteryTempC = maxOf(usable.map { it.batteryTempC })
        val firstTemp = usable.firstNotNullOfOrNull { it.batteryTempC }
        val deltaBatteryTempC =
            if (firstTemp != null && maxBatteryTempC != null) maxBatteryTempC - firstTemp else null
        val maxSocTempC = maxOf(usable.map { it.socTempC })

        val avgCpu0Khz = weighted(usable.map { it.cpu0Khz?.toDouble() to it.dtMs })
        val avgCpu6Khz = weighted(usable.map { it.cpu6Khz?.toDouble() to it.dtMs })
        val histogram0 = histogram(usable) { it.cpu0Khz }
        val histogram6 = histogram(usable) { it.cpu6Khz }
        val avgCpuLoadPct = weighted(usable.map { it.cpuLoadPct to it.dtMs })
        val avgGpuMhz = weighted(usable.map { it.gpuMhz?.toDouble() to it.dtMs })
        val avgGpuLoadPct = weighted(usable.map { it.gpuLoadPct to it.dtMs })

        val fpsValues = usable.mapNotNull { it.fps?.toDouble() }
            .filter { it > com.omarea.util.fps.FpsSample.MIN_VALID_FPS }
        val fpsSorted = fpsValues.sorted()
        val fpsAvg = if (fpsValues.isEmpty()) null else fpsValues.average()
        val fpsP95 = if (fpsSorted.isEmpty()) null else percentile(fpsSorted, 0.95)
        val fpsMin = fpsSorted.firstOrNull()
        val frames = usable.filter { (it.fpsFrames ?: 0) > 0 }.sumOf { it.fpsFrames ?: 0 }
        val janks = usable.filter { (it.fpsFrames ?: 0) > 0 }.sumOf { (it.fpsJank ?: 0).coerceAtLeast(0) }
        val jankPct = if (frames > 0) janks.toDouble() / frames * 100.0 else null

        val workFirst = usable.first().workUnits
        val workLast = usable.last().workUnits
        val workUnits = (workLast - workFirst).coerceAtLeast(0)
        val workPerSec = if (hours > 0 && workUnits > 0) workUnits / (durationMs / 1000.0) else null
        val mwhPerKiloWork = if (mwh != null && workUnits > 0) {
            mwh / (workUnits / 1000.0)
        } else null

        return Summary(
            scenario = scenario,
            samples = usable.size,
            durationMs = durationMs,
            avgBatteryMa = avgBatteryMa,
            medianBatteryMa = medianBatteryMa,
            maxAbsBatteryMa = maxAbsBatteryMa,
            avgUsbMa = avgUsbMa,
            avgMw = avgMw,
            maxMw = maxMw,
            mah = mah,
            mwh = mwh,
            pctOfDesign = pctOfDesign,
            pctPerHour = pctPerHour,
            avgBatteryTempC = avgBatteryTempC,
            maxBatteryTempC = maxBatteryTempC,
            deltaBatteryTempC = deltaBatteryTempC,
            maxSocTempC = maxSocTempC,
            avgCpu0Khz = avgCpu0Khz,
            avgCpu6Khz = avgCpu6Khz,
            freqHistogram0 = histogram0,
            freqHistogram6 = histogram6,
            avgCpuLoadPct = avgCpuLoadPct,
            avgGpuMhz = avgGpuMhz,
            avgGpuLoadPct = avgGpuLoadPct,
            fpsAvg = fpsAvg,
            fpsP95 = fpsP95,
            fpsMin = fpsMin,
            jankPct = jankPct,
            workUnits = workUnits,
            workPerSec = workPerSec,
            mwhPerKiloWork = mwhPerKiloWork
        )
    }

    fun percentile(sortedAscending: List<Double>, p: Double): Double {
        if (sortedAscending.isEmpty()) return 0.0
        val index = ((sortedAscending.size - 1) * p.coerceIn(0.0, 1.0)).toInt()
        return sortedAscending[index]
    }

    private fun histogram(
        samples: List<BenchSample>,
        selector: (BenchSample) -> Long?
    ): Map<Long, Double> {
        val map = HashMap<Long, Double>()
        for (sample in samples) {
            val freq = selector(sample) ?: continue
            map[freq] = (map[freq] ?: 0.0) + sample.dtMs.coerceIn(100L, 15_000L) / 1000.0
        }
        return map.toSortedMap()
    }

    // ------------------------------------------------------------- run level
    data class RunSummary(
        val mode: BenchMode,
        val target: String,
        val capacityStartPct: Int?,
        val capacityEndPct: Int?,
        val capacityDeltaPct: Int?,
        val maxBatteryTempC: Double?,
        val totalMwh: Double?,
        val scenarios: List<Summary>
    )

    fun summarizeRun(
        mode: BenchMode,
        target: String,
        samples: List<BenchSample>,
        designCapacityMah: Double?
    ): RunSummary {
        val firstCapacity = samples.firstNotNullOfOrNull { it.capacityPct }
        val lastCapacity = samples.asReversed().firstNotNullOfOrNull { it.capacityPct }
        val totalMwh = samples.fold(0.0) { acc, s ->
            val p = if (mode == BenchMode.CHARGER) s.usbMw else s.batteryMw
            if (p == null) acc else acc + abs(p) * s.dtMs.coerceIn(100L, 15_000L) / 3_600_000.0
        }.takeIf { samples.any { s -> (if (mode == BenchMode.CHARGER) s.usbMw else s.batteryMw) != null } }
        val byScenario = samples.groupBy { it.scenario }
            .map { (_, list) -> summarize(list, mode, designCapacityMah) }
        return RunSummary(
            mode = mode,
            target = target,
            capacityStartPct = firstCapacity,
            capacityEndPct = lastCapacity,
            capacityDeltaPct = if (firstCapacity != null && lastCapacity != null) lastCapacity - firstCapacity else null,
            maxBatteryTempC = samples.mapNotNull { it.batteryTempC }.maxOrNull(),
            totalMwh = totalMwh,
            scenarios = byScenario
        )
    }
}
