package com.omarea.util.fps

import kotlin.math.abs
import kotlin.math.max

/**
 * Session statistics for FPS records.
 *
 * Responsibility: derive dt-weighted, sentinel-free metrics from recorded
 * samples: average, min/max, smooth ratio (normalised to the *detected*
 * refresh), jank ratio, fever ratio, target refresh.
 *
 * Non-goals: reading/storing samples (FpsWatchStore) and UI.
 */
object FpsMetrics {

    /** One recorded point (mapped from `fps_history`). */
    data class Point(
        val fps: Float,
        val dtMs: Long,
        val refreshHz: Int = 0,
        val jankFrames: Int = -1,
        val frames: Int = -1,
        val temperature: Float = -1f,
        /** Milliseconds since the session started (0 = first sample). */
        val elapsedMs: Long = 0,
        val valid: Boolean = true
    ) {
        val usable: Boolean get() = valid && fps > FpsSample.MIN_VALID_FPS && fps < FpsSample.MAX_REFRESH
    }

    data class Summary(
        val avgFps: Float?,
        val minFps: Float?,
        val maxFps: Float?,
        /** Percent of (weighted) time at/above [smoothThreshold]; 0..100. */
        val smoothRatio: Double?,
        val smoothThreshold: Float,
        /** Dropped/janky frames as percent of rendered frames; 0..100. */
        val jankRatio: Double?,
        /** Percent of samples above the fever threshold; 0..100. */
        val feverRatio: Double?,
        val maxTemperature: Float?,
        val targetHz: Int,
        val samples: Int
    )

    fun percentile(sortedAscending: List<Float>, p: Double): Float {
        if (sortedAscending.isEmpty()) return 0f
        val index = ((sortedAscending.size - 1) * p.coerceIn(0.0, 1.0)).toInt()
        return sortedAscending[index]
    }

    /**
     * Target refresh: the harshest plausible standard refresh the session ran
     * at, derived from the observed P95 (a locked-60 game on a 120Hz panel is
     * judged against 60 - that is what the user actually sees).
     */
    fun detectRefreshHz(points: List<Point>): Int {
        val values = points.filter { it.usable }.map { it.fps }.sorted()
        if (values.isEmpty()) return 60
        val p95 = percentile(values, 0.95)
        val standard = intArrayOf(60, 90, 120, 144, 165)
        var nearest = standard[0]
        for (hz in standard) {
            if (abs(hz - p95) < abs(nearest - p95)) nearest = hz
        }
        return max(60, nearest)
    }

    /**
     * @param graceMs samples before this elapsed offset are ignored (recording
     *        start-up noise).
     */
    fun summarize(
        points: List<Point>,
        graceMs: Long = 2000,
        feverThreshold: Float = 46f
    ): Summary {
        val usable = points.filter { it.usable && it.elapsedMs >= graceMs }
        val target = detectRefreshHz(usable)
        if (usable.isEmpty()) {
            return Summary(null, null, null, null, 0f, null, null, null, target, 0)
        }
        // Smooth threshold: 90% of target (>=54 on a 60Hz panel), never below 1.
        val threshold = max(1f, target * 0.9f - 1f)
        val weight = { p: Point -> p.dtMs.coerceIn(100L, 10_000L).toDouble() }
        val totalWeight = usable.sumOf(weight)
        val avg = usable.sumOf { it.fps * weight(it) } / totalWeight
        val min = usable.minOf { it.fps }
        val max = usable.maxOf { it.fps }
        val smooth = usable.filter { it.fps >= threshold }.sumOf(weight) / totalWeight * 100.0

        val frames = usable.filter { it.frames > 0 }.sumOf { it.frames }
        val janks = usable.filter { it.jankFrames >= 0 }.sumOf { it.jankFrames }
        val jankRatio = if (frames > 0 && usable.any { it.jankFrames >= 0 }) {
            janks.toDouble() / frames * 100.0
        } else null

        val temps = usable.map { it.temperature }.filter { it > 5f }
        val fever = if (temps.isEmpty()) null else temps.count { it > feverThreshold } * 100.0 / temps.size
        return Summary(
            avgFps = avg.toFloat(),
            minFps = min,
            maxFps = max,
            smoothRatio = smooth,
            smoothThreshold = threshold,
            jankRatio = jankRatio,
            feverRatio = fever,
            maxTemperature = temps.maxOrNull(),
            targetHz = target,
            samples = usable.size
        )
    }
}
