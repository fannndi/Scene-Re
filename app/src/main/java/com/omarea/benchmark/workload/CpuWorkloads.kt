package com.omarea.benchmark.workload

import android.app.Activity
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import com.omarea.benchmark.BenchScenario
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Small helper: a labelled placeholder view for non-visual workloads. */
internal fun stubView(activity: Activity, text: String): TextView = TextView(activity).apply {
    this.text = text
    gravity = Gravity.CENTER
    textSize = 18f
}

internal fun attachStub(activity: Activity, container: FrameLayout, text: String) {
    container.removeAllViews()
    container.addView(
        stubView(activity, text),
        FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
    )
}

/** Idle: screen on, no artificial load. */
class IdleWorkload : BenchmarkWorkload {
    override val scenario = BenchScenario.IDLE
    override fun attach(activity: Activity, container: FrameLayout) =
        attachStub(activity, container, "Idle — screen on, no load")

    override fun start() {}
    override fun stop() {}
    override fun workUnits(): Long = 0L
}

/**
 * CPU: one thread per core running integer + floating point + a small PI-style
 * loop, counting iterations. Ported from the classic AndroidBenchmark loops.
 */
class CpuWorkload : BenchmarkWorkload {
    override val scenario = BenchScenario.CPU

    private val iterations = AtomicLong(0)
    private val threads = ArrayList<Thread>()
    @Volatile private var running = false

    override fun attach(activity: Activity, container: FrameLayout) =
        attachStub(activity, container, "CPU — multi-thread integer/float/PI load")

    override fun start() {
        if (running) return
        running = true
        iterations.set(0)
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        for (core in 0 until cores) {
            val thread = Thread({ loop() }, "bench-cpu-$core").apply { isDaemon = true }
            threads.add(thread)
            thread.start()
        }
    }

    private fun loop() {
        var sink = 1.0
        while (running) {
            // integer arithmetic
            for (i in 1..20_000) {
                sink += (i % 3) + (i % 7)
            }
            // floating point
            for (i in 1..20_000) {
                sink += i * 0.000001
                sink -= i * 0.0000005
            }
            // PI-ish series (caching + branching)
            var term = 1.0
            var sum = 0.0
            for (i in 1..2_000) {
                sum += term / (2 * i - 1)
                term = -term
            }
            if (sink.isInfinite() || sum.isNaN()) sink = 1.0
            if (sink < -1e300) sink = 0.0
            iterations.addAndGet(42_000)
        }
    }

    override fun stop() {
        running = false
        threads.forEach { it.interrupt() }
        threads.clear()
    }

    override fun workUnits(): Long = iterations.get()
}

/** Storage IO: fixed 8 MB file, 4 KB buffer, write + read loops. */
class IoWorkload(private val cacheDir: File) : BenchmarkWorkload {
    override val scenario = BenchScenario.IO

    private val bytes = AtomicLong(0)
    @Volatile private var running = false
    private var thread: Thread? = null

    override fun attach(activity: Activity, container: FrameLayout) =
        attachStub(activity, container, "Storage IO — 4 KB buffer write/read")

    override fun start() {
        if (running) return
        running = true
        bytes.set(0)
        thread = Thread({
            val file = File(cacheDir, "bench-io.bin")
            val buffer = ByteArray(4 * 1024) { (it % 251).toByte() }
            while (running) {
                try {
                    file.outputStream().use { out ->
                        var written = 0L
                        while (running && written < FILE_SIZE) {
                            out.write(buffer)
                            written += buffer.size
                            bytes.addAndGet(buffer.size.toLong())
                        }
                    }
                    if (!running) break
                    file.inputStream().use { input ->
                        while (running) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            bytes.addAndGet(read.toLong())
                        }
                    }
                } catch (ex: Exception) {
                    break
                }
            }
            runCatching { file.delete() }
        }, "bench-io").apply { isDaemon = true }
        thread?.start()
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    override fun workUnits(): Long = bytes.get()

    private companion object {
        const val FILE_SIZE = 8L * 1024 * 1024
    }
}
