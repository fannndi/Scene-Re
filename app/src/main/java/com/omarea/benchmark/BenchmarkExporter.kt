package com.omarea.benchmark

import android.content.Context
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileWriter

/**
 * Benchmark bundle writer.
 *
 * Responsibility: write the machine-readable dataset under
 * `<externalFiles>/benchmark/<stamp>-<mode>/`: `meta.txt`, per-run
 * `samples.csv` (1 row/second), `summary.json`, `report.md` and the suite
 * report at the root.
 *
 * Non-goals: computing values (callers pass summaries) and UI.
 */
class BenchmarkExporter(
    context: Context,
    stamp: String,
    private val mode: BenchMode
) {
    val root: File = File(
        context.getExternalFilesDir(null) ?: context.filesDir,
        "benchmark/$stamp-${mode.name.lowercase()}"
    ).apply { mkdirs() }

    fun runDir(target: String): File = File(root, "run-$target").apply { mkdirs() }

    /** Streaming CSV writer for the per-second samples. */
    class SampleWriter(private val dir: File) : Closeable {
        private val writer = BufferedWriter(FileWriter(File(dir, "samples.csv"), false), 16 * 1024)

        init {
            writer.write(HEADER)
            writer.newLine()
        }

        fun append(sample: BenchSample) {
            val row = buildString {
                append(sample.elapsedMs).append(',')
                append(sample.dtMs).append(',')
                append(sample.scenario.id).append(',')
                append(sample.batteryMv ?: "").append(',')
                append(sample.batteryMa ?: "").append(',')
                append(fmt(sample.batteryMw)).append(',')
                append(sample.usbMv ?: "").append(',')
                append(sample.usbMa ?: "").append(',')
                append(fmt(sample.usbMw)).append(',')
                append(sample.usbType.orEmpty()).append(',')
                append(fmt(sample.batteryTempC)).append(',')
                append(sample.capacityPct ?: "").append(',')
                append(fmt(sample.socTempC)).append(',')
                append(fmt(sample.cpu0TempC)).append(',')
                append(fmt(sample.gpussTempC)).append(',')
                append(fmt(sample.ddrTempC)).append(',')
                append(sample.cpu0Khz ?: "").append(',')
                append(sample.cpu0MinKhz ?: "").append(',')
                append(sample.cpu0MaxKhz ?: "").append(',')
                append(sample.cpu6Khz ?: "").append(',')
                append(sample.cpu6MinKhz ?: "").append(',')
                append(sample.cpu6MaxKhz ?: "").append(',')
                append(fmt(sample.cpuLoadPct)).append(',')
                append(sample.gpuMhz ?: "").append(',')
                append(fmt(sample.gpuLoadPct)).append(',')
                append(fmt(sample.fps?.toDouble())).append(',')
                append(sample.fpsFrames ?: "").append(',')
                append(sample.fpsJank ?: "").append(',')
                append(sample.workUnits)
            }
            writer.write(row)
            writer.newLine()
            writer.flush()
        }

        private fun fmt(value: Double?): String =
            if (value == null) "" else String.format(java.util.Locale.US, "%.3f", value)

        override fun close() {
            runCatching {
                writer.flush()
                writer.close()
            }
        }

        private companion object {
            const val HEADER =
                "elapsed_ms,dt_ms,scenario,battery_mv,battery_ma,battery_mw,usb_mv,usb_ma,usb_mw,usb_type," +
                    "battery_temp_c,capacity_pct,soc_temp_c,cpu0_temp_c,gpuss_temp_c,ddr_temp_c," +
                    "cpu0_khz,cpu0_min_khz,cpu0_max_khz,cpu6_khz,cpu6_min_khz,cpu6_max_khz," +
                    "cpu_load_pct,gpu_mhz,gpu_load_pct,fps,fps_frames,fps_jank,work_units"
        }
    }

    fun writeMeta(dir: File, meta: Map<String, String>) {
        runCatching {
            File(dir, "meta.txt").writeText(
                meta.entries.joinToString("\n") { (key, value) -> "$key: $value" } + "\n"
            )
        }
    }

    fun writeRunReport(dir: File, run: BenchmarkReport.RunData) {
        runCatching { File(dir, "report.md").writeText(BenchmarkReport.runMarkdown(run)) }
        runCatching {
            File(dir, "summary.json").writeText(BenchmarkReport.suiteJson(run.mode, listOf(run)))
        }
    }

    fun writeRunSummaryLine(dir: File, text: String) {
        runCatching { File(dir, "verdict.txt").writeText(text + "\n") }
    }

    fun writeSuite(runs: List<BenchmarkReport.RunData>) {
        runCatching { File(root, "report.md").writeText(BenchmarkReport.suiteMarkdown(mode, runs)) }
        runCatching { File(root, "summary.json").writeText(BenchmarkReport.suiteJson(mode, runs)) }
    }
}
