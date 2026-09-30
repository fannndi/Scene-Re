package com.omarea.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BenchmarkMetricsTest {

    private fun sample(
        scenario: BenchScenario = BenchScenario.CPU,
        dt: Long = 1000,
        batteryMa: Int? = -500,
        batteryMv: Int? = 3800,
        usbMa: Int? = null,
        usbMv: Int? = 5000,
        temp: Double? = 35.0,
        cpu0Khz: Long? = 1_000_000,
        cpu6Khz: Long? = 2_000_000,
        work: Long = 0
    ) = BenchSample(
        elapsedMs = 0,
        dtMs = dt,
        scenario = scenario,
        batteryMv = batteryMv,
        batteryMa = batteryMa,
        usbMv = usbMv,
        usbMa = usbMa,
        batteryTempC = temp,
        cpu0Khz = cpu0Khz,
        cpu6Khz = cpu6Khz,
        workUnits = work
    )

    @Test
    fun `discharge integral and drain percent are honest`() {
        val samples = (0 until 10).map { sample(dt = 1000, batteryMa = -500, work = it * 1000L) }
        val summary = BenchmarkMetrics.summarize(samples, BenchMode.DISCHARGE, designCapacityMah = 5000.0)

        assertEquals(10, summary.samples)
        assertEquals(10_000L, summary.durationMs)
        assertEquals(-500.0, summary.avgBatteryMa!!, 1e-6)
        assertEquals(1900.0, summary.avgMw!!, 1e-6)          // 3800mV * 500mA / 1000
        assertEquals(-1.388888, summary.mah!!, 1e-5)         // -500mA * 10s / 3600
        assertEquals(-0.027777, summary.pctOfDesign!!, 1e-5)
        assertEquals(-10.0, summary.pctPerHour!!, 1e-3)      // -0.0278% over 10s
        assertEquals(35.0, summary.avgBatteryTempC!!, 1e-6)
        assertEquals(0.0, summary.deltaBatteryTempC!!, 1e-6)
        assertEquals(1_000_000.0, summary.avgCpu0Khz!!, 1e-6)
        assertEquals(10.0, summary.freqHistogram0[1_000_000L]!!, 1e-6)
        assertEquals(9_000L, summary.workUnits)              // last-first
        assertEquals(900.0, summary.workPerSec!!, 1e-6)
    }

    @Test
    fun `charger mode uses input power for mw and battery for mah`() {
        val samples = (0 until 10).map { sample(usbMa = 500, batteryMa = 300, work = it * 1000L) }
        val summary = BenchmarkMetrics.summarize(samples, BenchMode.CHARGER, designCapacityMah = 5000.0)

        assertEquals(2500.0, summary.avgMw!!, 1e-6)          // 5000mV * 500mA / 1000
        assertEquals(500.0, summary.avgUsbMa!!, 1e-6)
        assertEquals(0.833333, summary.mah!!, 1e-5)          // +300mA * 10s / 3600
        assertEquals(300.0, summary.avgBatteryMa!!, 1e-6)
        assertEquals(6.944444, summary.mwh!!, 1e-5)          // 2500mW * 10s / 3.6e6
        assertEquals(0.771605, summary.mwhPerKiloWork!!, 1e-5)
    }

    @Test
    fun `empty input stays empty`() {
        val summary = BenchmarkMetrics.summarize(emptyList(), BenchMode.DISCHARGE)
        assertEquals(0, summary.samples)
        assertNull(summary.avgMw)
        assertNull(summary.fpsAvg)
    }

    @Test
    fun `fps and jank aggregate only valid render samples`() {
        val samples = listOf(
            sample(scenario = BenchScenario.GPU, dt = 1000).copy(fps = 60f, fpsFrames = 100, fpsJank = 1),
            sample(scenario = BenchScenario.GPU, dt = 1000).copy(fps = 30f, fpsFrames = 100, fpsJank = 5),
            sample(scenario = BenchScenario.GPU, dt = 1000).copy(fps = 1.0f, fpsFrames = 1, fpsJank = 0),
            sample(scenario = BenchScenario.GPU, dt = 1000).copy(fps = null)
        )
        val summary = BenchmarkMetrics.summarize(samples, BenchMode.DISCHARGE)
        assertEquals(45.0, summary.fpsAvg!!, 1e-6)           // (60+30)/2, the 1f sentinel is excluded
        assertEquals(30.0, summary.fpsMin!!, 1e-6)
        assertEquals(6.0 / 201.0 * 100.0, summary.jankPct!!, 1e-4)
    }

    @Test
    fun `run summary reports capacity delta`() {
        val samples = listOf(
            sample().copy(capacityPct = 80),
            sample().copy(capacityPct = 79),
            sample().copy(capacityPct = 78)
        )
        val run = BenchmarkMetrics.summarizeRun(BenchMode.DISCHARGE, "powersave", samples, 5000.0)
        assertEquals(80, run.capacityStartPct)
        assertEquals(78, run.capacityEndPct)
        assertEquals(-2, run.capacityDeltaPct)
        assertEquals(1, run.scenarios.size)
    }
}
