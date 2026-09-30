package com.omarea.benchmark

/**
 * Benchmark data model (pure).
 *
 * Responsibility: scenario/target descriptors, run configuration and the
 * per-second sample record. All measurements are nullable: unknown is `null`,
 * never a sentinel value.
 *
 * Non-goals: sampling (BenchmarkSampler), aggregation (BenchmarkMetrics),
 * orchestration (BenchmarkRunner).
 */

enum class BenchMode { DISCHARGE, CHARGER }

enum class BenchScenario(val id: String, val label: String) {
    IDLE("idle", "Idle (screen on)"),
    SCROLL("scroll", "UI scroll"),
    CPU("cpu", "CPU multi-thread"),
    GPU("gpu", "GPU render"),
    MIXED("mixed", "Mixed (game-like)"),
    IO("io", "Storage IO"),
    VIDEO("video", "Video playback");

    /** Scenarios whose workload renders frames worth measuring FPS for. */
    val needsFps: Boolean get() = this == SCROLL || this == GPU || this == MIXED || this == VIDEO
}

/** Comparison targets: stock baseline + the three engine profiles. */
data class BenchTarget(val id: String, val label: String) {
    companion object {
        val STOCK = BenchTarget("stock", "Stock (engine OFF)")
        val POWERSAVE = BenchTarget("powersave", "Power saving")
        val BALANCE = BenchTarget("balance", "Balance")
        val PERFORMANCE = BenchTarget("performance", "Performance")
        val ALL = listOf(STOCK, POWERSAVE, BALANCE, PERFORMANCE)

        fun byId(id: String): BenchTarget? = ALL.firstOrNull { it.id == id }
    }
}

data class BenchConfig(
    val mode: BenchMode,
    val targets: List<String>,
    val scenarios: List<BenchScenario>,
    val warmupSeconds: Int = 5,
    val measureSeconds: Int = 60,
    /** Wait for the device to cool down to this delta above the pre-run temp. */
    val cooldownTargetDeltaC: Double = 1.5,
    val cooldownMaxMs: Long = 5 * 60_000L,
    val maxBatteryTempC: Double = 55.0,
    val maxSocTempC: Double = 95.0,
    val minCapacityPct: Int = 15
)

/** One sampled second. `dtMs` is the real spacing, so integrals are honest. */
data class BenchSample(
    val elapsedMs: Long,
    val dtMs: Long,
    val scenario: BenchScenario,
    val batteryMv: Int? = null,
    val batteryMa: Int? = null,
    val usbMv: Int? = null,
    val usbMa: Int? = null,
    val usbType: String? = null,
    val batteryTempC: Double? = null,
    val capacityPct: Int? = null,
    val socTempC: Double? = null,
    val cpu0TempC: Double? = null,
    val gpussTempC: Double? = null,
    val ddrTempC: Double? = null,
    val cpu0Khz: Long? = null,
    val cpu0MinKhz: Long? = null,
    val cpu0MaxKhz: Long? = null,
    val cpu6Khz: Long? = null,
    val cpu6MinKhz: Long? = null,
    val cpu6MaxKhz: Long? = null,
    val cpuLoadPct: Double? = null,
    val gpuMhz: Long? = null,
    val gpuLoadPct: Double? = null,
    val fps: Float? = null,
    val fpsFrames: Int? = null,
    val fpsJank: Int? = null,
    val workUnits: Long = 0L
) {
    /** Battery-side power in mW = (mV/1000) * (mA/1000) * 1000. */
    val batteryMw: Double? get() = powerMw(batteryMv, batteryMa)

    /** Charger input power in mW (usb is µV/µA -> already mA here). */
    val usbMw: Double? get() = powerMw(usbMv, usbMa)

    private fun powerMw(mv: Int?, ma: Int?): Double? =
        if (mv == null || ma == null) null else mv.toDouble() * ma.toDouble() / 1000.0
}
