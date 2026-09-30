package com.omarea.util.fps

import android.os.SystemClock
import com.omarea.common.shell.KeepShell

/**
 * Per-app FPS + jank telemetry from `dumpsys gfxinfo <pkg> framestats`.
 *
 * Responsibility: keep a sliding window of `FrameCompleted` timestamps, then
 * derive FPS from the *actual* window span plus jank metrics (deltas above
 * 2x the median, P95 frame time). No sentinels: `null` means "no data".
 *
 * Non-goals: source selection / fallbacks (FpsSampler) and storage.
 */
class GfxInfoSampler(private val keepShell: KeepShell) {
    private var lastActivePackage: String? = null
    private val frameTimeBuffer = ArrayList<Long>()
    private var lastProcessedFrameTime = 0L
    private var gfxColumnIndex = -1
    private var lastFrameSeenAtMs = 0L

    @Synchronized
    fun sample(packageName: String): FpsSample? {
        if (packageName != lastActivePackage) {
            lastActivePackage = packageName
            frameTimeBuffer.clear()
            lastProcessedFrameTime = 0L
            gfxColumnIndex = -1
            keepShell.doCmdSync("dumpsys gfxinfo $packageName reset")
            return null
        }

        val output = keepShell.doCmdSync("dumpsys gfxinfo $packageName framestats")
        if (output.isEmpty() || output == "error") {
            return null
        }

        val lines = output.split("\n")
        if (gfxColumnIndex == -1) {
            val header = lines.firstOrNull { it.contains("FrameCompleted") } ?: return null
            gfxColumnIndex = header.split(",").indexOf("FrameCompleted")
            if (gfxColumnIndex == -1) {
                return null
            }
        }

        var maxTimestamp = lastProcessedFrameTime
        var addedFrames = 0
        for (line in lines) {
            val parts = line.split(",")
            if (parts.size <= gfxColumnIndex) {
                continue
            }
            val timestamp = parts[gfxColumnIndex].trim().toLongOrNull() ?: continue
            if (timestamp <= lastProcessedFrameTime) {
                continue
            }
            frameTimeBuffer.add(timestamp)
            addedFrames += 1
            if (timestamp > maxTimestamp) {
                maxTimestamp = timestamp
            }
        }
        lastProcessedFrameTime = maxTimestamp

        if (addedFrames > 0) {
            lastFrameSeenAtMs = SystemClock.elapsedRealtime()
        } else if (lastFrameSeenAtMs > 0 && SystemClock.elapsedRealtime() - lastFrameSeenAtMs > 2000) {
            // App stopped rendering (paused/closed): drop the ring and re-arm.
            frameTimeBuffer.clear()
            lastProcessedFrameTime = 0L
            gfxColumnIndex = -1
            keepShell.doCmdSync("dumpsys gfxinfo $packageName reset")
            return null
        }

        val nowNs = System.nanoTime()
        frameTimeBuffer.removeAll { nowNs - it > 1_000_000_000L }
        if (frameTimeBuffer.size < 8) {
            return null
        }

        val sorted = frameTimeBuffer.sorted()
        val spanNs = sorted.last() - sorted.first()
        if (spanNs < 250_000_000L) {
            // Too narrow for a stable estimate (min ~250 ms window).
            return null
        }
        val fps = (sorted.size - 1) * 1_000_000_000f / spanNs

        val deltas = ArrayList<Float>(sorted.size - 1)
        for (i in 1 until sorted.size) {
            deltas.add((sorted[i] - sorted[i - 1]) / 1_000_000f)
        }
        deltas.sort()
        val median = deltas[deltas.size / 2]
        val jankLimit = maxOf(2f * median, 24f)
        val jankFrames = deltas.count { it > jankLimit }
        val p95 = deltas[((deltas.size - 1) * 0.95).toInt().coerceIn(0, deltas.size - 1)]

        return FpsSample(
            fps = fps,
            source = "gfxinfo",
            frames = deltas.size,
            spanMs = spanNs / 1_000_000,
            jankFrames = jankFrames,
            p95FrameMs = p95
        ).takeIf { it.valid }
    }
}
