package com.omarea.benchmark

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Benchmark report rendering (Markdown + JSON).
 *
 * Responsibility: turn [BenchmarkMetrics.Summary] data into an AnTuTu-style
 * comparison — battery percent drained per target, per-scenario power/thermal/
 * frequency/FPS tables and an automatic verdict against the stock baseline.
 *
 * Non-goals: sampling and storage.
 */
object BenchmarkReport {

    data class RunData(
        val mode: BenchMode,
        val target: String,
        val startedAt: Long,
        val capacityStart: Int?,
        val capacityEnd: Int?,
        val maxBatteryTempC: Double?,
        val tuningHash: String?,
        val summaries: List<BenchmarkMetrics.Summary>,
        val aborted: Boolean,
        val abortReason: String?,
        val meta: Map<String, String> = emptyMap()
    )

    // ------------------------------------------------------------------ run
    fun runMarkdown(run: RunData): String {
        val sb = StringBuilder()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(run.startedAt))
        sb.appendLine("# Benchmark — ${BenchTarget.byId(run.target)?.label ?: run.target} (${modeLabel(run.mode)})")
        sb.appendLine()
        sb.appendLine("- started     : $stamp")
        sb.appendLine("- battery     : ${run.capacityStart ?: "?"}% → ${run.capacityEnd ?: "?"}%" +
            (deltaText(run.capacityStart, run.capacityEnd)?.let { "  ($it)" } ?: ""))
        sb.appendLine("- max temp    : ${fmt(run.maxBatteryTempC, "%.1f")}°C")
        sb.appendLine("- tuning hash : ${run.tuningHash ?: "-"}")
        if (run.aborted) sb.appendLine("- ABORTED     : ${run.abortReason ?: "yes"}")
        sb.appendLine()
        sb.appendLine(scenarioTable(run.summaries))
        sb.appendLine()
        sb.appendLine(histogramSection(run.summaries))
        return sb.toString()
    }

    // ---------------------------------------------------------------- suite
    fun suiteMarkdown(mode: BenchMode, runs: List<RunData>): String {
        val sb = StringBuilder()
        sb.appendLine("# Scene benchmark suite — ${modeLabel(mode)}")
        sb.appendLine()
        sb.appendLine("- runs: ${runs.joinToString(", ") { it.target }}")
        sb.appendLine()
        sb.appendLine("## Battery drained per profile (observed)")
        sb.appendLine()
        sb.appendLine("| target | battery start→end | Δ% | total mWh | max temp |")
        sb.appendLine("|---|---|---|---|---|")
        for (run in runs) {
            val label = BenchTarget.byId(run.target)?.label ?: run.target
            sb.appendLine(
                "| $label | ${run.capacityStart ?: "?"}% → ${run.capacityEnd ?: "?"}% | " +
                    "${deltaText(run.capacityStart, run.capacityEnd) ?: "-"} | " +
                    "${fmt(run.totalMwh(), "%.1f")} | ${fmt(run.maxBatteryTempC, "%.1f")}°C |"
            )
        }
        sb.appendLine()
        sb.appendLine("## Per-scenario comparison")
        sb.appendLine()
        sb.appendLine(comparisonTable(mode, runs))
        sb.appendLine()
        sb.appendLine("## Verdict vs stock")
        sb.appendLine()
        sb.appendLine(verdict(runs))
        sb.appendLine()
        sb.appendLine("_Columns: avg power (mW, battery side or USB input side), drain/charge (% of design), " +
            "FPS (render scenarios), mWh per 1000 work units._")
        return sb.toString()
    }

    private fun RunData.totalMwh(): Double? =
        summaries.mapNotNull { it.mwh }.takeIf { it.isNotEmpty() }?.sum()

    private fun comparisonTable(mode: BenchMode, runs: List<RunData>): String {
        val scenarios = runs.flatMap { it.summaries }.map { it.scenario }.distinct()
        val header = "| scenario | " + runs.joinToString(" | ") { BenchTarget.byId(it.target)?.label ?: it.target } + " |"
        val divider = "|---|" + runs.joinToString("|") { "---" } + "|"
        val sb = StringBuilder()
        sb.appendLine(header)
        sb.appendLine(divider)
        for (scenario in scenarios) {
            val cells = runs.map { run ->
                val summary = run.summaries.firstOrNull { it.scenario == scenario }
                if (summary == null) "-" else {
                    val power = fmt(summary.avgMw, "%.0f") + "mW"
                    val pct = fmt(summary.pctOfDesign?.let { abs(it) }, "%.2f") + "%"
                    val fps = summary.fpsAvg?.let { " · " + fmt(it, "%.1f") + "fps" } ?: ""
                    "$power · $pct$fps"
                }
            }
            sb.appendLine("| ${scenario.label} | " + cells.joinToString(" | ") + " |")
        }
        return sb.toString()
    }

    private fun verdict(runs: List<RunData>): String {
        val stock = runs.firstOrNull { it.target == BenchTarget.STOCK.id } ?: return "_(stock baseline not run)_"
        val sb = StringBuilder()
        for (run in runs.filter { it.target != BenchTarget.STOCK.id }) {
            val label = BenchTarget.byId(run.target)?.label ?: run.target
            val perScenario = run.summaries.mapNotNull { summary ->
                val base = stock.summaries.firstOrNull { it.scenario == summary.scenario } ?: return@mapNotNull null
                val parts = ArrayList<String>()
                powerDelta(base.avgMw, summary.avgMw)?.let { parts.add("power ${fmtSigned(it)}%") }
                energyPerWorkDelta(base, summary)?.let { parts.add("energy/work ${fmtSigned(it)}%") }
                fpsDelta(base.fpsAvg, summary.fpsAvg)?.let { parts.add("fps ${fmtSigned(it)}%") }
                tempDelta(base, summary)?.let { parts.add("temp ${fmtSigned(it)}°C") }
                if (parts.isEmpty()) null else "${summary.scenario.label}: ${parts.joinToString(", ")}"
            }
            sb.appendLine("- **$label** — " + (perScenario.joinToString(" · ").ifEmpty { "no comparable data" }))
        }
        return sb.toString()
    }

    private fun powerDelta(base: Double?, value: Double?): Double? =
        if (base == null || value == null || abs(base) < 1.0) null else (value - base) / abs(base) * 100.0

    private fun energyPerWorkDelta(base: BenchmarkMetrics.Summary, value: BenchmarkMetrics.Summary): Double? {
        val b = base.mwhPerKiloWork ?: return null
        val v = value.mwhPerKiloWork ?: return null
        if (abs(b) < 1e-6) return null
        return (v - b) / abs(b) * 100.0
    }

    private fun fpsDelta(base: Double?, value: Double?): Double? =
        if (base == null || value == null || base < 1.0) null else (value - base) / base * 100.0

    private fun tempDelta(base: BenchmarkMetrics.Summary, value: BenchmarkMetrics.Summary): Double? {
        val b = base.avgBatteryTempC ?: return null
        val v = value.avgBatteryTempC ?: return null
        return v - b
    }

    // ---------------------------------------------------------------- parts
    private fun scenarioTable(summaries: List<BenchmarkMetrics.Summary>): String {
        val sb = StringBuilder()
        sb.appendLine("| scenario | avg mW | max mW | mAh | %/h | avg°C | max°C | f0 avg | f6 avg | gpu | fps avg/p95 | work/s | mWh/ku |")
        sb.appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        for (s in summaries) {
            sb.appendLine(
                "| ${s.scenario.label} " +
                    "| ${fmt(s.avgMw, "%.0f")} " +
                    "| ${fmt(s.maxMw, "%.0f")} " +
                    "| ${fmt(s.mah, "%.2f")} " +
                    "| ${fmt(s.pctPerHour, "%.1f")} " +
                    "| ${fmt(s.avgBatteryTempC, "%.1f")} " +
                    "| ${fmt(s.maxBatteryTempC, "%.1f")} " +
                    "| ${mhz(s.avgCpu0Khz)} " +
                    "| ${mhz(s.avgCpu6Khz)} " +
                    "| ${fmt(s.avgGpuMhz, "%.0f")}MHz/${fmt(s.avgGpuLoadPct, "%.0f")}% " +
                    "| ${fmt(s.fpsAvg, "%.1f")}${s.fpsP95?.let { "/" + fmt(it, "%.1f") } ?: ""} " +
                    "| ${fmt(s.workPerSec, "%.0f")} " +
                    "| ${fmt(s.mwhPerKiloWork, "%.3f")} |"
            )
        }
        return sb.toString()
    }

    private fun histogramSection(summaries: List<BenchmarkMetrics.Summary>): String {
        val sb = StringBuilder("## Time in frequency (top buckets)\n\n")
        for (s in summaries) {
            if (s.freqHistogram0.isEmpty() && s.freqHistogram6.isEmpty()) continue
            sb.appendLine("### ${s.scenario.label}")
            sb.appendLine("- little: " + topBuckets(s.freqHistogram0))
            sb.appendLine("- big   : " + topBuckets(s.freqHistogram6))
        }
        return sb.toString()
    }

    private fun topBuckets(histogram: Map<Long, Double>, count: Int = 4): String =
        histogram.entries.sortedByDescending { it.value }.take(count)
            .joinToString(", ") { "${mhz(it.key.toDouble())}MHz=${fmt(it.value, "%.0f")}s" }
            .ifEmpty { "-" }

    // ----------------------------------------------------------------- json
    fun suiteJson(mode: BenchMode, runs: List<RunData>): String {
        val root = JSONObject()
        root.put("mode", mode.name)
        root.put("generatedAt", System.currentTimeMillis())
        val array = JSONArray()
        for (run in runs) {
            val obj = JSONObject()
            obj.put("target", run.target)
            obj.put("targetLabel", BenchTarget.byId(run.target)?.label ?: run.target)
            obj.put("startedAt", run.startedAt)
            obj.put("capacityStartPct", run.capacityStart ?: JSONObject.NULL)
            obj.put("capacityEndPct", run.capacityEnd ?: JSONObject.NULL)
            obj.put("capacityDeltaPct", deltaInt(run.capacityStart, run.capacityEnd) ?: JSONObject.NULL)
            obj.put("maxBatteryTempC", run.maxBatteryTempC ?: JSONObject.NULL)
            obj.put("totalMwh", run.totalMwh() ?: JSONObject.NULL)
            obj.put("tuningHash", run.tuningHash ?: JSONObject.NULL)
            obj.put("aborted", run.aborted)
            obj.put("abortReason", run.abortReason ?: JSONObject.NULL)
            val scenarios = JSONArray()
            for (s in run.summaries) {
                scenarios.put(JSONObject().apply {
                    put("scenario", s.scenario.id)
                    put("samples", s.samples)
                    put("durationMs", s.durationMs)
                    put("avgBatteryMa", s.avgBatteryMa ?: JSONObject.NULL)
                    put("medianBatteryMa", s.medianBatteryMa ?: JSONObject.NULL)
                    put("maxAbsBatteryMa", s.maxAbsBatteryMa ?: JSONObject.NULL)
                    put("avgUsbMa", s.avgUsbMa ?: JSONObject.NULL)
                    put("avgMw", s.avgMw ?: JSONObject.NULL)
                    put("maxMw", s.maxMw ?: JSONObject.NULL)
                    put("mah", s.mah ?: JSONObject.NULL)
                    put("mwh", s.mwh ?: JSONObject.NULL)
                    put("pctOfDesign", s.pctOfDesign ?: JSONObject.NULL)
                    put("pctPerHour", s.pctPerHour ?: JSONObject.NULL)
                    put("avgBatteryTempC", s.avgBatteryTempC ?: JSONObject.NULL)
                    put("maxBatteryTempC", s.maxBatteryTempC ?: JSONObject.NULL)
                    put("deltaBatteryTempC", s.deltaBatteryTempC ?: JSONObject.NULL)
                    put("maxSocTempC", s.maxSocTempC ?: JSONObject.NULL)
                    put("avgCpu0Khz", s.avgCpu0Khz ?: JSONObject.NULL)
                    put("avgCpu6Khz", s.avgCpu6Khz ?: JSONObject.NULL)
                    put("avgCpuLoadPct", s.avgCpuLoadPct ?: JSONObject.NULL)
                    put("avgGpuMhz", s.avgGpuMhz ?: JSONObject.NULL)
                    put("avgGpuLoadPct", s.avgGpuLoadPct ?: JSONObject.NULL)
                    put("fpsAvg", s.fpsAvg ?: JSONObject.NULL)
                    put("fpsP95", s.fpsP95 ?: JSONObject.NULL)
                    put("fpsMin", s.fpsMin ?: JSONObject.NULL)
                    put("jankPct", s.jankPct ?: JSONObject.NULL)
                    put("workUnits", s.workUnits)
                    put("workPerSec", s.workPerSec ?: JSONObject.NULL)
                    put("mwhPerKiloWork", s.mwhPerKiloWork ?: JSONObject.NULL)
                    put("avgBatteryMaRange", s.avgBatteryMaRange ?: JSONObject.NULL)
                    put("avgCpuLoadRange", s.avgCpuLoadRange ?: JSONObject.NULL)
                    put("avgGpuLoadRange", s.avgGpuLoadRange ?: JSONObject.NULL)
                    put("freqHistogram0", JSONObject(s.freqHistogram0.mapKeys { it.key.toString() }))
                    put("freqHistogram6", JSONObject(s.freqHistogram6.mapKeys { it.key.toString() }))
                })
            }
            obj.put("scenarios", scenarios)
            array.put(obj)
        }
        root.put("runs", array)
        return root.toString(2)
    }

    // ----------------------------------------------------------------- util
    fun modeLabel(mode: BenchMode) = if (mode == BenchMode.CHARGER) "charger (input power)" else "discharge (battery power)"

    private fun deltaText(start: Int?, end: Int?): String? = deltaInt(start, end)?.let { fmtSigned(it.toDouble()) + "%" }

    private fun deltaInt(start: Int?, end: Int?): Int? =
        if (start == null || end == null) null else end - start

    private fun mhz(khz: Double?): String =
        if (khz == null) "-" else String.format(Locale.US, "%.0f", khz / 1000.0)

    private fun fmt(value: Double?, format: String): String =
        if (value == null) "-" else String.format(Locale.US, format, value)

    private fun fmtSigned(value: Double): String =
        (if (value >= 0) "+" else "") + String.format(Locale.US, "%.1f", value)
}
