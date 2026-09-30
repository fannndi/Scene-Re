package com.omarea.benchmark.workload

import android.app.Activity
import android.media.MediaPlayer
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.omarea.benchmark.BenchScenario
import java.util.concurrent.atomic.AtomicLong

/**
 * UI scroll: a long list scrolled programmatically every frame.
 * Work counter = scrolled pixels.
 */
class ScrollWorkload : BenchmarkWorkload {
    override val scenario = BenchScenario.SCROLL

    private var recycler: RecyclerView? = null
    private val scrolled = AtomicLong(0)
    @Volatile private var running = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            recycler?.let { view ->
                view.scrollBy(0, 14)
                scrolled.addAndGet(14)
                if (!view.canScrollVertically(1)) {
                    view.scrollToPosition(0)
                }
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun attach(activity: Activity, container: FrameLayout) {
        container.removeAllViews()
        val list = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = RowAdapter()
        }
        recycler = list
        container.addView(
            list,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
    }

    override fun start() {
        scrolled.set(0)
        running = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    override fun workUnits(): Long = scrolled.get()

    private class RowAdapter : RecyclerView.Adapter<RowAdapter.Row>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row =
            Row(LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(parent.context).apply { textSize = 16f })
            })

        override fun getItemCount(): Int = 600

        override fun onBindViewHolder(holder: Row, position: Int) {
            (holder.itemView as LinearLayout).getChildAt(0).let {
                (it as TextView).text = "Scene benchmark row $position — scroll load"
            }
        }

        class Row(view: LinearLayout) : RecyclerView.ViewHolder(view)
    }
}

/**
 * Video: the bundled H.264 clip played in a loop (identical file every run).
 * Work counter = estimated decoded frames (30 fps nominal).
 */
class VideoWorkload : BenchmarkWorkload {
    override val scenario = BenchScenario.VIDEO

    private var surfaceView: SurfaceView? = null
    private var player: MediaPlayer? = null
    @Volatile private var prepared = false
    @Volatile private var startAt = 0L
    @Volatile private var failed = false

    override fun attach(activity: Activity, container: FrameLayout) {
        container.removeAllViews()
        val surface = SurfaceView(activity)
        surfaceView = surface
        container.addView(
            surface,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = prepare(activity, holder)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })
    }

    private fun prepare(activity: Activity, holder: SurfaceHolder) {
        if (player != null) return
        try {
            val assets = activity.assets
            val afd = assets.openFd(ASSET)
            val mp = MediaPlayer()
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mp.setDisplay(holder)
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setOnPreparedListener {
                prepared = true
                if (startPending) it.start()
            }
            mp.setOnErrorListener { _, _, _ ->
                failed = true
                true
            }
            mp.prepareAsync()
            player = mp
        } catch (ex: Exception) {
            failed = true
        }
    }

    @Volatile private var startPending = false

    override fun start() {
        startAt = System.currentTimeMillis()
        startPending = true
        if (prepared) {
            runCatching { player?.start() }
        }
    }

    override fun stop() {
        startPending = false
        runCatching { player?.pause() }
    }

    override fun workUnits(): Long {
        if (startAt <= 0L || failed) return 0L
        // 30 fps nominal: frames = elapsed ms * 30 / 1000
        return (System.currentTimeMillis() - startAt) * 30 / 1000
    }

    private companion object {
        const val ASSET = "bench/video-720p30.mp4"
    }
}

/**
 * Mixed (game-like): GPU rendering + CPU threads + memory churn at once.
 * Work counter = GPU frames.
 */
class MixedWorkload : BenchmarkWorkload {
    override val scenario = BenchScenario.MIXED

    private val gpu = GpuWorkload()
    private val churnIterations = AtomicLong(0)
    @Volatile private var running = false
    private var churnThread: Thread? = null

    override fun attach(activity: Activity, container: FrameLayout) = gpu.attach(activity, container)

    override fun start() {
        running = true
        gpu.start()
        churnThread = Thread({ churn() }, "bench-mem").apply { isDaemon = true }
        churnThread?.start()
    }

    private fun churn() {
        val buffers = ArrayList<ByteArray>(8)
        while (running) {
            try {
                buffers.add(ByteArray(1024 * 1024) { 1 })
                if (buffers.size > 8) buffers.removeAt(0)
                churnIterations.incrementAndGet()
                Thread.sleep(2)
            } catch (ex: Exception) {
                break
            }
        }
        buffers.clear()
    }

    override fun stop() {
        running = false
        gpu.stop()
        churnThread?.interrupt()
        churnThread = null
    }

    override fun workUnits(): Long = gpu.workUnits()
}
