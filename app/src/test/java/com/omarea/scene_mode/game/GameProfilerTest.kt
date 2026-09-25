package com.omarea.scene_mode.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameProfilerTest {
    private val t0 = 1_000_000L

    private fun feed(pkg: String, gpu: Double?, fps: Double?, samples: Int = 6): String? {
        GameProfiler.reset(pkg)
        var decision: String? = null
        for (i in 0 until samples) {
            decision = GameProfiler.observe(pkg, gpu, fps, t0 + i * 10_000L)
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
    fun shortWindowStaysUndecided() {
        assertNull(feed("com.test.short", 20.0, 55.0, samples = 4))
    }
}
