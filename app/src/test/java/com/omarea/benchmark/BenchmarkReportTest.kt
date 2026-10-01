package com.omarea.benchmark

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The suite report carries the verdict numbers — its math is the product.
 */
class BenchmarkReportTest {

    private fun sample(
        scenario: BenchScenario,
        batteryMa: Int,
        fps: Float? = null
    ) = BenchSample(
        elapsedMs = 0,
        dtMs = 1000,
        scenario = scenario,
        batteryMv = 4000,
        batteryMa = batteryMa,
        batteryTempC = 35.0,
        fps = fps,
        fpsFrames = fps?.let { 60 } ?: null,
        fpsJank = fps?.let { 1 } ?: null
    )

    private fun run(
        target: String,
        batteryMa: Int,
        fps: Float?,
        capacityStart: Int = 80,
        capacityEnd: Int = 78
    ): BenchmarkReport.RunData {
        val samples = listOf(
            sample(BenchScenario.CPU, batteryMa, fps),
            sample(BenchScenario.CPU, batteryMa, fps)
        )
        return BenchmarkReport.RunData(
            mode = BenchMode.DISCHARGE,
            target = target,
            startedAt = 0L,
            capacityStart = capacityStart,
            capacityEnd = capacityEnd,
            maxBatteryTempC = 36.0,
            tuningHash = "abc123",
            summaries = listOf(BenchmarkMetrics.summarize(samples, BenchMode.DISCHARGE, 5000.0)),
            aborted = false,
            abortReason = null
        )
    }

    @Test
    fun `verdict computes power, fps and temp deltas against stock`() {
        val stock = run(BenchTarget.STOCK.id, batteryMa = -250, fps = 60f)
        val save = run(BenchTarget.POWERSAVE.id, batteryMa = -175, fps = 57f)
        val md = BenchmarkReport.suiteMarkdown(BenchMode.DISCHARGE, listOf(stock, save))

        // 4000mV x -175mA vs x -250mA -> 700mW vs 1000mW -> -30.0%
        assertTrue("missing power delta: $md", md.contains("power -30.0%"))
        // 57 fps vs 60 fps -> -5.0%
        assertTrue("missing fps delta: $md", md.contains("fps -5.0%"))
        // Same temps -> +0.0°C
        assertTrue("missing temp delta: $md", md.contains("temp +0.0°C"))
        // Battery drained rows
        assertTrue("missing battery table: $md", md.contains("80% → 78%"))
        assertTrue("missing delta: $md", md.contains("-2.0%"))
    }

    @Test
    fun `verdict without stock baseline says so instead of lying`() {
        val save = run(BenchTarget.POWERSAVE.id, batteryMa = -175, fps = 57f)
        val md = BenchmarkReport.suiteMarkdown(BenchMode.DISCHARGE, listOf(save))
        assertTrue("missing baseline note: $md", md.contains("stock baseline not run"))
    }

    @Test
    fun `suite json carries every aggregate an agent needs`() {
        val stock = run(BenchTarget.STOCK.id, batteryMa = -250, fps = 60f)
        val json = JSONObject(BenchmarkReport.suiteJson(BenchMode.DISCHARGE, listOf(stock)))

        assertEquals("DISCHARGE", json.getString("mode"))
        val runs = json.getJSONArray("runs")
        assertEquals(1, runs.length())
        val first = runs.getJSONObject(0)
        assertEquals("stock", first.getString("target"))
        assertEquals(80, first.getInt("capacityStartPct"))
        assertEquals(-2, first.getInt("capacityDeltaPct"))
        assertEquals("abc123", first.getString("tuningHash"))

        val scenario = first.getJSONArray("scenarios").getJSONObject(0)
        assertEquals("cpu", scenario.getString("scenario"))
        assertTrue(scenario.getDouble("avgMw") > 0)
        assertTrue(scenario.getDouble("mah") < 0)      // discharge is negative
        assertTrue(scenario.has("avgBatteryMaRange"))
        assertTrue(scenario.getJSONObject("freqHistogram0").length() >= 0)
    }
}
