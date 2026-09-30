package com.omarea.benchmark.workload

import android.app.Activity
import android.widget.FrameLayout
import com.omarea.benchmark.BenchScenario

/**
 * A benchmark workload: content that produces a measurable, repeatable load.
 *
 * Responsibility: start/stop the load and report a cumulative work counter
 * (iterations, frames, bytes) so energy can be normalised per work unit.
 * Non-goals: sampling (BenchmarkSampler) and orchestration (BenchmarkRunner).
 */
interface BenchmarkWorkload {
    val scenario: BenchScenario

    /** Attach the workload view; called on the main thread. */
    fun attach(activity: Activity, container: FrameLayout)

    fun start()

    fun stop()

    /** Cumulative work counter; reset when the workload is created. */
    fun workUnits(): Long
}
