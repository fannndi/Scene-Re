package com.omarea.util.measure

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-parameter measurement log.
 *
 * Responsibility: append one pipe-separated line per measured parameter to a
 * rotating daily CSV under `<externalFiles>/measure`, written off the main
 * thread. This is the evidence trail for measurement accuracy: each line
 * carries the source, unit and validity of the sample.
 *
 * Non-goals: statistics/parsing (the stores own their data) and UI.
 *
 * Format: `yyyy-MM-dd HH:mm:ss.SSS|parameter|value|unit|source|valid|extra`
 */
object MeasureLog {
    /** Killing switch (diagnostics may prefer a quiet run). */
    @Volatile
    var enabled: Boolean = true

    private const val MAX_BYTES = 2L * 1024 * 1024
    private const val KEEP_FILES = 7

    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "measure-log").apply { isDaemon = true }
    }
    private val droppedSamples = AtomicLong(0)

    @Volatile
    private var logDir: File? = null

    /** Called from `Scene.attachBaseContext`; safe to call repeatedly. */
    fun init(context: Context) {
        logDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "measure").apply { mkdirs() }
    }

    /**
     * Records one parameter sample. [value] `null` is skipped (callers pass
     * `null` for invalid reads, so the log stays honest).
     */
    fun sample(
        parameter: String,
        value: Any?,
        unit: String = "",
        source: String = "",
        valid: Boolean = true,
        extra: String = ""
    ) {
        if (!enabled || value == null) return
        val dir = logDir ?: return
        val line = buildString {
            append(format.format(Date())).append('|')
            append(parameter).append('|')
            append(value).append('|')
            append(unit).append('|')
            append(source).append('|')
            append(if (valid) "1" else "0")
            if (extra.isNotEmpty()) {
                append('|')
                append(extra)
            }
        }
        executor.execute { appendLine(dir, line) }
    }

    /** Current log directory (for diagnostics / adb pull). */
    fun directory(): File? = logDir

    /** Human-readable status for the diagnostics report. */
    fun status(): String {
        val dir = logDir ?: return "not initialised"
        val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        val totalKb = files.sumOf { it.length() } / 1024
        return "${files.size} file(s), ${totalKb}KB in ${dir.absolutePath}, dropped=${droppedSamples.get()}"
    }

    // ------------------------------------------------------------------ io
    private fun appendLine(dir: File, line: String) {
        try {
            var file = File(dir, "measure-${dayStamp()}.csv")
            if (file.length() > MAX_BYTES) {
                val rotated = File(dir, "measure-${dayStamp()}-${System.currentTimeMillis()}.csv")
                if (file.renameTo(rotated)) {
                    file = File(dir, "measure-${dayStamp()}.csv")
                }
            }
            file.appendText(line + "\n")
            purgeOld(dir)
        } catch (ex: Exception) {
            droppedSamples.incrementAndGet()
        }
    }

    private fun purgeOld(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile && it.name.startsWith("measure-") } ?: return
        if (files.size <= KEEP_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - KEEP_FILES)
            .forEach { runCatching { it.delete() } }
    }

    private fun dayStamp(): String {
        val now = Date()
        return SimpleDateFormat("yyyyMMdd", Locale.US).format(now)
    }
}
