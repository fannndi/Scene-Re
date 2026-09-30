package com.omarea.util.fps

/**
 * One FPS observation with provenance.
 *
 * [valid] rejects sentinels and impossible values; callers must never store an
 * invalid sample as a real number (the old `1f` sentinel poisoned min/avg).
 */
data class FpsSample(
    val fps: Float,
    /** measured_fps | gfxinfo | fpsgo | sf_counter */
    val source: String,
    /** Frames observed in the measurement window, -1 when unknown. */
    val frames: Int = -1,
    /** Window length in ms, -1 when unknown. */
    val spanMs: Long = -1,
    /** Dropped/janky frames in the window, -1 when unknown. */
    val jankFrames: Int = -1,
    /** 95th percentile frame time in ms, -1 when unknown. */
    val p95FrameMs: Float = -1f
) {
    val valid: Boolean get() = fps > MIN_VALID_FPS && fps < 500f

    companion object {
        /** Highest plausible refresh; anything above is a parsing artefact. */
        const val MAX_REFRESH = 500f

        /**
         * Values at/below this are treated as invalid: the legacy sampler
         * emitted exactly `1f` on failure, and no real session needs statistics
         * polluted by it.
         */
        const val MIN_VALID_FPS = 2f
    }
}

/**
 * Parses the `measured_fps` sysfs format used by this kernel family:
 *
 *     fps: 54.5 duration:1000000 frame_count:55
 *
 * Older kernels expose just `fps: 60`. Pure so it is JVM-testable.
 */
object MeasuredFpsParser {
    private val FPS = Regex("""fps:\s*([0-9]+(?:\.[0-9]+)?)""")
    private val DURATION = Regex("""duration:\s*([0-9]+)""")
    private val FRAMES = Regex("""frame_count:\s*([0-9]+)""")

    fun parse(raw: String?): FpsSample? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val fps = FPS.find(text)?.groupValues?.get(1)?.toFloatOrNull() ?: return null
        val durationUs = DURATION.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
        val frames = FRAMES.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val sample = FpsSample(
            fps = fps,
            source = "measured_fps",
            frames = frames,
            spanMs = if (durationUs > 0) durationUs / 1000 else -1L
        )
        // This is a real kernel counter, so even a low idle-screen rate is a
        // genuine reading (unlike the legacy 1f sentinel); only impossible
        // values are rejected.
        return sample.takeIf { it.fps > 0.5f && it.fps < FpsSample.MAX_REFRESH }
    }
}

/**
 * Parses the fpsgo frame-stats table (`/sys/kernel/fpsgo/fstb/fpsgo_status`).
 * Header starts with `tid` and carries `name` / `currentFPS` columns; only
 * rows matching the wanted package are considered, and the median of the
 * matches is used instead of the old "max wins" heuristic.
 */
object FpsgoParser {
    fun parse(raw: String?, packageName: String?): Float? {
        val text = raw?.trim().orEmpty()
        val pkg = packageName?.trim().orEmpty()
        if (text.isEmpty() || pkg.isEmpty()) return null

        var nameIndex = -1
        var fpsIndex = -1
        val matches = ArrayList<Float>()
        for (row in text.split("\n")) {
            val line = row.trim()
            if (line.isEmpty()) continue
            val cols = line.split(Regex("\\s+"))
            if (line.startsWith("tid")) {
                nameIndex = cols.indexOf("name")
                fpsIndex = cols.indexOf("currentFPS")
                continue
            }
            if (nameIndex < 0 || fpsIndex < 0) continue
            if (cols.size <= fpsIndex || cols.size <= nameIndex) continue
            if (!nameMatches(cols[nameIndex], pkg)) continue
            val fps = cols[fpsIndex].toFloatOrNull() ?: continue
            if (fps > FpsSample.MIN_VALID_FPS && fps < FpsSample.MAX_REFRESH) matches.add(fps)
        }
        if (matches.isEmpty()) return null
        matches.sort()
        return matches[matches.size / 2]
    }

    private fun nameMatches(name: String, packageName: String): Boolean {
        if (packageName == name) return true
        if (packageName.endsWith(name)) return true
        val tail = packageName.substringAfterLast(".")
        return name == tail || name.endsWith(tail)
    }
}
