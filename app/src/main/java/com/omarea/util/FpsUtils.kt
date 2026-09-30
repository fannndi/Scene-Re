package com.omarea.util

import com.omarea.util.fps.FpsSampler
import java.util.Locale

/**
 * FPS display facade.
 *
 * Responsibility: format the [FpsSampler] reading for UI consumers.
 * Non-goals: source selection (FpsSampler) and storage (FpsWatchStore).
 */
class FpsUtils {
    private val sampler = FpsSampler()

    /** Formatted for display, e.g. `59.4`; null when no source is valid. */
    val currentFps: String?
        get() = sampler.currentFps()?.let { String.format(Locale.US, "%.1f", it) }

    /** Numeric fps, 0f when no source is valid (never 1f). */
    val fps: Float
        get() = sampler.currentFps() ?: 0f
}
