package com.omarea.scene_mode.game

/**
 * Cheap light/heavy classifier for game sessions.
 *
 * The profiler watches the GPU busy percentage (kgsl) and the measured display
 * frame rate while a game is in the foreground. A game that keeps the GPU below
 * [LIGHT_GPU_BUSY] percent over a full window while still presenting frames is
 * treated as light; one that keeps the GPU above [HEAVY_GPU_BUSY] is heavy.
 * Everything in between stays undecided, so an unknown game is never
 * downgraded on a guess — it simply keeps the performance profile.
 *
 * Both control paths share this object: the accessibility service through
 * GameSessionTracker and the app_process monitor through its own loop. Only the
 * decision is shared, the persistence stays with the caller.
 */
object GameProfiler {
    const val CLASS_HEAVY = "heavy"
    const val CLASS_LIGHT = "light"

    /** A window must cover this much wall time before a decision is allowed. */
    private const val WINDOW_MS = 45_000L
    private const val MIN_SAMPLES = 6
    private const val MIN_GPU_SAMPLES = 4
    private const val LIGHT_GPU_BUSY = 35.0
    private const val HEAVY_GPU_BUSY = 60.0
    private const val LIGHT_MIN_FPS = 24.0

    private class Window {
        var startedAt = 0L
        var samples = 0
        var gpuSamples = 0
        var gpuSum = 0.0
        var fpsSamples = 0
        var fpsSum = 0.0
    }

    private val windows = HashMap<String, Window>()

    /**
     * Decision that still needs a confirming window. A change is only reported
     * after two consecutive windows agree, so a loading screen that keeps the
     * GPU idle for a moment cannot downgrade a heavy game.
     */
    private val pending = HashMap<String, String>()

    /**
     * Feed one sample for [packageName]. Returns [CLASS_LIGHT], [CLASS_HEAVY]
     * when two consecutive windows agreed, or null while undecided. A window is
     * always consumed, so a game that stays in the middle is re-evaluated from
     * scratch instead of accumulating stale samples. [now] is injectable for
     * tests.
     */
    @Synchronized
    fun observe(
        packageName: String,
        gpuBusy: Double?,
        fps: Double?,
        now: Long = System.currentTimeMillis()
    ): String? {
        if (packageName.isEmpty()) {
            return null
        }
        val window = windows.getOrPut(packageName) {
            Window().also { it.startedAt = now }
        }
        window.samples++
        if (gpuBusy != null && gpuBusy >= 0.0) {
            window.gpuSamples++
            window.gpuSum += gpuBusy
        }
        if (fps != null && fps > 1.0) {
            window.fpsSamples++
            window.fpsSum += fps
        }
        if (now - window.startedAt < WINDOW_MS || window.samples < MIN_SAMPLES) {
            return null
        }
        val decision = decide(window)
        windows.remove(packageName)
        if (decision == null) {
            // The streak has to be consecutive.
            pending.remove(packageName)
            return null
        }
        if (pending[packageName] == decision) {
            pending.remove(packageName)
            return decision
        }
        pending[packageName] = decision
        return null
    }

    private fun decide(window: Window): String? {
        if (window.gpuSamples < MIN_GPU_SAMPLES) {
            return null
        }
        val gpu = window.gpuSum / window.gpuSamples
        if (gpu >= HEAVY_GPU_BUSY) {
            return CLASS_HEAVY
        }
        val fps = if (window.fpsSamples > 0) window.fpsSum / window.fpsSamples else null
        if (gpu < LIGHT_GPU_BUSY && (fps == null || fps >= LIGHT_MIN_FPS)) {
            return CLASS_LIGHT
        }
        return null
    }

    @Synchronized
    fun reset(packageName: String) {
        windows.remove(packageName)
        pending.remove(packageName)
    }
}
