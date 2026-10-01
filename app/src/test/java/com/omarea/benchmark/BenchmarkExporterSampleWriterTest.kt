package com.omarea.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The CSV is the contract agents parse — header/row parity and null handling
 * are logic, not cosmetics.
 */
class BenchmarkExporterSampleWriterTest {

    private fun tempDir(): File =
        Files.createTempDirectory("bench-export").toFile()

    private fun fullSample() = BenchSample(
        elapsedMs = 1000,
        dtMs = 1000,
        scenario = BenchScenario.CPU,
        batteryMv = 3900,
        batteryMa = -500,
        usbMv = null,
        usbMa = null,
        usbType = null,
        batteryTempC = 31.5,
        capacityPct = 56,
        socTempC = 70.0,
        cpu0TempC = 60.0,
        gpussTempC = 40.0,
        ddrTempC = 38.0,
        cpu0Khz = 1_804_800,
        cpu0MinKhz = 300_000,
        cpu0MaxKhz = 1_804_800,
        cpu6Khz = 2_304_000,
        cpu6MinKhz = 300_000,
        cpu6MaxKhz = 2_304_000,
        cpuLoadPct = 99.5,
        gpuMhz = 180,
        gpuLoadPct = 12.0,
        fps = 59.5f,
        fpsFrames = 60,
        fpsJank = 1,
        workUnits = 12_345L,
        cpu0KhzMin = 300_000,
        cpu0KhzMax = 1_804_800,
        cpu6KhzMin = 300_000,
        cpu6KhzMax = 2_304_000,
        cpuLoadMin = 80.0,
        cpuLoadMax = 100.0,
        gpuMhzMin = 180,
        gpuMhzMax = 180,
        gpuLoadMin = 5.0,
        gpuLoadMax = 20.0,
        batteryMaMin = -600,
        batteryMaMax = -400
    )

    @Test
    fun `header and every row have identical column counts`() {
        val dir = tempDir()
        BenchmarkExporter.SampleWriter(dir).use { writer ->
            writer.append(fullSample())
            // Nulls everywhere: must still emit the same column count.
            writer.append(
                BenchSample(
                    elapsedMs = 2000,
                    dtMs = 1000,
                    scenario = BenchScenario.IDLE,
                    batteryMv = 4100,
                    batteryMa = 300
                )
            )
        }
        val lines = File(dir, "samples.csv").readLines()
        assertEquals(3, lines.size)

        val header = lines[0].split(",")
        val headerSet = header.toSet()
        assertEquals(header.size, headerSet.size) // no duplicate columns
        for (line in lines.drop(1)) {
            assertEquals("column count drift", header.size, line.split(",").size)
        }
    }

    @Test
    fun `values land in their named columns and nulls stay empty`() {
        val dir = tempDir()
        BenchmarkExporter.SampleWriter(dir).use { writer ->
            writer.append(fullSample())
        }
        val lines = File(dir, "samples.csv").readLines()
        val header = lines[0].split(",")
        val row = lines[1].split(",")

        fun col(name: String): String {
            val index = header.indexOf(name)
            assertTrue("missing column $name", index >= 0)
            return row[index]
        }

        assertEquals("cpu", col("scenario"))
        assertEquals("3900", col("battery_mv"))
        assertEquals("-500", col("battery_ma"))
        assertEquals("-1950.000", col("battery_mw"))   // 3900mV x -500mA / 1000
        assertEquals("", col("usb_mv"))          // null -> empty, never a sentinel
        assertEquals("", col("usb_type"))
        assertEquals("99.500", col("cpu_load_pct"))
        assertEquals("59.500", col("fps"))
        assertEquals("12345", col("work_units"))
        assertEquals("-600", col("battery_ma_min"))
        assertEquals("-400", col("battery_ma_max"))
        assertEquals("300000", col("cpu0_khz_min"))
        assertEquals("1804800", col("cpu0_khz_max"))
    }

    @Test
    fun `power columns follow the canonical formula mV x mA`() {
        val dir = tempDir()
        BenchmarkExporter.SampleWriter(dir).use { writer ->
            writer.append(fullSample())
        }
        val lines = File(dir, "samples.csv").readLines()
        val header = lines[0].split(",")
        val row = lines[1].split(",")
        val batteryMw = row[header.indexOf("battery_mw")].toDouble()
        assertEquals(3900.0 * -500.0 / 1000.0, batteryMw, 1e-6)
        // Sign convention: discharge (negative mA) keeps the signed value in
        // the CSV; magnitude normalisation happens in BenchmarkMetrics.
        assertTrue(batteryMw < 0)
    }
}
