package com.omarea.util.fps

import android.os.SystemClock
import com.omarea.common.shell.KeepShellPublic
import com.omarea.util.measure.SysReader

/**
 * Unified FPS source ladder.
 *
 * Responsibility: return one trustworthy [FpsSample] (or `null`) from the best
 * available source, consistently for every consumer (record, overlays,
 * notification):
 *
 *   1. `measured_fps` sysfs - compositor-level, ~1 s rolling window, one cheap read
 *   2. `gfxinfo`          - per-app frame times, adds jank metrics
 *   3. `fpsgo`            - when the kernel exposes the fstb table
 *   4. SurfaceFlinger frame counter - legacy fallback, disabled permanently
 *      the first time it fails
 *
 * Non-goals: polling cadence (callers own their timers) and storage.
 */
class FpsSampler {
    private val gfx = GfxInfoSampler(KeepShellPublic.getInstance("gfxinfo-fps", true))
    private val frameCounter = SurfaceFlingerFrameCounter()

    private var measuredProbed = false
    private var measuredPath: String? = null

    private var topPackageAt = 0L
    private var topPackageCache: String? = null

    @Synchronized
    fun sample(packageName: String? = null): FpsSample? {
        readMeasured()?.let { return it }
        val pkg = packageName?.takeIf { it.isNotEmpty() } ?: topPackage()
        if (!pkg.isNullOrEmpty()) {
            gfx.sample(pkg)?.let { return it }
            readFpsgo(pkg)?.let { return it }
        }
        return frameCounter.sample()
    }

    /** Convenience for UI: fps or null (never a 1f/0f sentinel). */
    fun currentFps(packageName: String? = null): Float? = sample(packageName)?.fps

    // ------------------------------------------------------------ measured_fps
    private fun readMeasured(): FpsSample? {
        if (!measuredProbed) {
            measuredProbed = true
            measuredPath = probeMeasuredFps()
        }
        val path = measuredPath ?: return null
        val raw = SysReader.readFirst(path) ?: return null
        return MeasuredFpsParser.parse(raw)
    }

    private fun probeMeasuredFps(): String? {
        for (path in MEASURED_FPS_PATHS) {
            if (SysReader.readFirst(path) != null) return path
        }
        // One-off discovery for unusual kernels.
        val found = KeepShellPublic.doCmdSync(
            "find /sys -name measured_fps 2>/dev/null | grep crtc | head -1"
        ).trim()
        return found.takeIf { it.isNotEmpty() && it != "error" && !it.startsWith("find") }
    }

    // ------------------------------------------------------------------ fpsgo
    private fun readFpsgo(packageName: String): FpsSample? {
        val raw = SysReader.readFirst(FPSGO_STATUS_PATH) ?: return null
        val fps = FpsgoParser.parse(raw, packageName) ?: return null
        val sample = FpsSample(fps, "fpsgo")
        return sample.takeIf { it.valid }
    }

    // ----------------------------------------------------------- top package
    private fun topPackage(): String? {
        val now = SystemClock.elapsedRealtime()
        if (now - topPackageAt < 2000L) return topPackageCache
        topPackageAt = now
        topPackageCache = detectTopPackage()
        return topPackageCache
    }

    private fun detectTopPackage(): String? {
        val resumed = KeepShellPublic.doCmdSync(
            "dumpsys activity activities | grep -m 1 \"mResumedActivity\""
        ).trim()
        parsePackage(resumed)?.let { return it }
        val focus = KeepShellPublic.doCmdSync("dumpsys window | grep -m 1 \"mCurrentFocus\"").trim()
        return parsePackage(focus)
    }

    private fun parsePackage(line: String): String? {
        if (line.isEmpty() || line == "error") return null
        return Regex("([a-zA-Z0-9._-]+)/").find(line)?.groupValues?.get(1)
    }

    // ------------------------------------------------------ SF frame counter
    /** Legacy `service call SurfaceFlinger 1013`; gives up after first failure. */
    private class SurfaceFlingerFrameCounter {
        private var supported = true
        private var lastFrames = -1L
        private var lastAt = -1L

        @Synchronized
        fun sample(): FpsSample? {
            if (!supported) return null
            val out = KeepShellPublic.doCmdSync("service call SurfaceFlinger 1013").trim()
            val hex = Regex("Parcel\\(\\s*([0-9a-fA-F]{8})").find(out)?.groupValues?.get(1)
            if (hex == null) {
                supported = false
                return null
            }
            val frames = hex.toLong(16) and 0xFFFFFFFFL
            val now = SystemClock.elapsedRealtime()
            val fps = if (lastAt > 0 && frames >= lastFrames && now > lastAt) {
                (frames - lastFrames) * 1000f / (now - lastAt)
            } else null
            lastFrames = frames
            lastAt = now
            if (fps == null || fps <= 0.5f || fps >= FpsSample.MAX_REFRESH) return null
            return FpsSample(fps, "sf_counter")
        }
    }

    private companion object {
        val MEASURED_FPS_PATHS = listOf(
            "/sys/class/drm/sde-crtc-0/measured_fps",
            "/sys/class/graphics/fb0/measured_fps"
        )
        const val FPSGO_STATUS_PATH = "/sys/kernel/fpsgo/fstb/fpsgo_status"
    }
}
