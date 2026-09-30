package com.omarea.util.fps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FpsSampleParsersTest {

    @Test
    fun `measured fps parses the kernel format`() {
        val sample = MeasuredFpsParser.parse("fps: 54.5 duration:1000000 frame_count:55")!!
        assertEquals(54.5f, sample.fps, 0.001f)
        assertEquals(55, sample.frames)
        assertEquals(1000L, sample.spanMs)
        assertEquals("measured_fps", sample.source)
    }

    @Test
    fun `measured fps accepts the bare legacy format`() {
        val sample = MeasuredFpsParser.parse("fps: 60")!!
        assertEquals(60f, sample.fps, 0.001f)
        assertEquals(-1, sample.frames)
        assertEquals(-1L, sample.spanMs)
    }

    @Test
    fun `measured fps rejects sentinels and garbage`() {
        assertNull("1f sentinel", MeasuredFpsParser.parse("fps: 1.0 duration:1000000 frame_count:1"))
        assertNull("impossible", MeasuredFpsParser.parse("fps: 900 duration:1000000 frame_count:900"))
        assertNull("no data", MeasuredFpsParser.parse("no data"))
        assertNull("null", MeasuredFpsParser.parse(null))
    }

    @Test
    fun `fpsgo takes the median of matching rows`() {
        val table = """
            tid name currentFPS other
            1 com.game 60.0 x
            2 com.game 58.0 x
            3 com.game 59.0 x
            4 com.other 20.0 x
        """.trimIndent()
        assertEquals(59f, FpsgoParser.parse(table, "com.game")!!, 0.001f)
    }

    @Test
    fun `fpsgo ignores other packages and sentinel rows`() {
        val table = """
            tid name currentFPS
            1 com.game 1.0
        """.trimIndent()
        assertNull(FpsgoParser.parse(table, "com.game"))
        assertNull(FpsgoParser.parse(table, "com.none"))
        assertNull(FpsgoParser.parse(null, "com.game"))
    }
}

class FpsMetricsTest {

    private fun point(
        fps: Float,
        dt: Long,
        elapsed: Long,
        temperature: Float = -1f,
        jank: Int = -1,
        frames: Int = -1
    ) = FpsMetrics.Point(
        fps = fps,
        dtMs = dt,
        jankFrames = jank,
        frames = frames,
        temperature = temperature,
        elapsedMs = elapsed
    )

    @Test
    fun `summary is dt weighted and sentinel free`() {
        val summary = FpsMetrics.summarize(
            listOf(
                point(30f, 1000, 3000),   // 1 s stutter
                point(60f, 1000, 4000),   // 1 s at 60
                point(60f, 3000, 5000)    // 3 s at 60
            )
        )
        assertEquals(54f, summary.avgFps!!, 0.01f)
        assertEquals(30f, summary.minFps!!, 0.01f)
        assertEquals(60f, summary.maxFps!!, 0.01f)
        assertEquals(60, summary.targetHz)
        assertEquals(53, summary.smoothThreshold.toInt())
        assertEquals(80.0, summary.smoothRatio!!, 0.01)
        assertEquals(3, summary.samples)
    }

    @Test
    fun `grace window and invalid samples are excluded`() {
        val summary = FpsMetrics.summarize(
            listOf(
                point(1f, 1000, 1000),    // legacy sentinel inside grace
                point(60f, 1000, 1000),   // real sample inside grace
                point(60f, 1000, 3000)    // counted
            )
        )
        assertEquals(60f, summary.avgFps!!, 0.01f)
        assertEquals(1, summary.samples)
        assertEquals(60f, summary.minFps!!, 0.01f)
    }

    @Test
    fun `jank ratio aggregates frames and jank frames`() {
        val summary = FpsMetrics.summarize(
            listOf(
                point(60f, 1000, 3000, jank = 1, frames = 100),
                point(59f, 1000, 4000, jank = 2, frames = 200),
                point(60f, 1000, 5000) // no jank info
            )
        )
        assertEquals(1.0, summary.jankRatio!!, 0.001)
    }

    @Test
    fun `fever ratio uses only valid temperatures`() {
        val summary = FpsMetrics.summarize(
            listOf(
                point(60f, 1000, 3000, temperature = 47f),
                point(60f, 1000, 4000, temperature = 44f),
                point(60f, 1000, 5000, temperature = 48f),
                point(60f, 1000, 6000, temperature = -1f)
            )
        )
        assertEquals(2 * 100.0 / 3, summary.feverRatio!!, 0.01)
        assertEquals(48f, summary.maxTemperature!!, 0.01f)
    }

    @Test
    fun `target refresh follows high refresh sessions`() {
        val summary = FpsMetrics.summarize(
            listOf(
                point(120f, 1000, 3000),
                point(118f, 1000, 4000),
                point(120f, 1000, 5000)
            )
        )
        assertEquals(120, summary.targetHz)
        assertEquals(107, summary.smoothThreshold.toInt())
    }

    @Test
    fun `empty input produces an empty summary`() {
        val summary = FpsMetrics.summarize(emptyList())
        assertNull(summary.avgFps)
        assertNull(summary.smoothRatio)
        assertEquals(0, summary.samples)
    }
}
