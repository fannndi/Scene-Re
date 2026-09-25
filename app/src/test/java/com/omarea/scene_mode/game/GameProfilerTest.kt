package com.omarea.scene_mode.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameProfilerTest {
    private val t0 = 1_000_000L

    /** Feed [windows] full windows of identical samples, 6 samples each. */
    private fun feed(pkg: String, gpu: Double?, fps: Double?, windows: Int = 2): String? {
        GameProfiler.reset(pkg)
        var decision: String? = null
        for (w in 0 until windows) {
            for (i in 0 until 6) {
                decision = GameProfiler.observe(pkg, gpu, fps, t0 + (w * 6 + i) * 10_000L)
            }
        }
        return decision
    }

    @Test
    fun idleGpuWithFramesIsLight() {
        assertEquals(GameProfiler.CLASS_LIGHT, feed("com.test.light", 20.0, 55.0))
    }

    @Test
    fun saturatedGpuIsHeavy() {
        assertEquals(GameProfiler.CLASS_HEAVY, feed("com.test.heavy", 85.0, 45.0))
    }

    @Test
    fun middleGpuStaysUndecided() {
        assertNull(feed("com.test.middle", 48.0, 60.0))
    }

    @Test
    fun lowFpsIsNotDowngradedToLight() {
        assertNull(feed("com.test.lowfps", 20.0, 12.0))
    }

    @Test
    fun missingGpuStaysUndecided() {
        assertNull(feed("com.test.nogpu", null, 60.0))
    }

    @Test
    fun singleWindowIsNotEnough() {
        assertNull(feed("com.test.short", 20.0, 55.0, windows = 1))
    }

    @Test
    fun disagreeingWindowsStayUndecided() {
        val pkg = "com.test.alternating"
        GameProfiler.reset(pkg)
        var decision: String? = null
        for (i in 0 until 6) {
            decision = GameProfiler.observe(pkg, 20.0, 55.0, t0 + i * 10_000L)
        }
        for (i in 0 until 6) {
            decision = GameProfiler.observe(pkg, 85.0, 45.0, t0 + (6 + i) * 10_000L)
        }
        assertNull(decision)
    }
}
