package com.omarea.utils

import android.content.Context
import android.util.Log
import com.omarea.store.SpfConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Central diagnostic log for Scene.
 *
 * Before this existed, diagnostics were spread across three unrelated places: the
 * crash handler wrote a stack trace to `Android/vtools-error.log`, `AppErrorLogcatUtils`
 * shelled out to `logcat` for a one-shot dump, and the floating debug overlay
 * (`FloatLogView`) showed only whatever the accessibility service pushed into it.
 * Nothing correlated the three, there was no severity level, no bounded history, and
 * no way for the USB test harness to read events back in a parseable form.
 *
 * Design constraints that shaped this class:
 *
 * - **Tags a machine can parse.** Every record starts with a fixed, column-aligned
 *   prefix (`LEVEL TAG: message`) so `adb logcat` output and the on-disk file can be
 *   grepped without guessing. The test harness depends on this.
 * - **Bounded memory.** The in-memory ring is capped, because this app runs on a
 *   phone that is often memory-constrained, and an unbounded log buffer is a leak.
 * - **Bounded disk.** The file rotates at a fixed size and keeps one previous
 *   generation, so a crash loop cannot fill storage.
 * - **Never throw.** Logging must not be able to crash the app it is diagnosing.
 *   Every public entry point swallows its own errors.
 * - **Off by default on disk.** `verbose` is enabled explicitly (Settings, or by the
 *   USB harness via a broadcast) rather than always writing files, so a release build
 *   does not pay the I/O cost or leak data.
 */
object SceneLog {

    // -- Severity ------------------------------------------------------------

    // Values match android.util.Log so they can be passed straight through.
    const val VERBOSE = Log.VERBOSE
    const val DEBUG = Log.DEBUG
    const val INFO = Log.INFO
    const val WARN = Log.WARN
    const val ERROR = Log.ERROR

    /** Single-letter level tags. Fixed width keeps columns aligned in the log file. */
    private val LEVEL_CHAR = mapOf(
        VERBOSE to "V",
        DEBUG to "D",
        INFO to "I",
        WARN to "W",
        ERROR to "E"
    )

    /** Global tag prefix, so Scene's own lines can be isolated from ROM noise. */
    const val TAG_PREFIX = "Scene"

    /**
     * Marker string the USB test harness greps for. Emitting results in this exact
     * format is what lets an LLM agent over `adb` read structured test outcomes
     * without needing an in-app UI.
     */
    const val TEST_MARKER = "SCENE_TEST"

    // -- Bounds --------------------------------------------------------------

    /** Maximum records kept in memory. Older records are evicted. */
    private const val MAX_MEMORY_RECORDS = 2000

    /** Rotate the on-disk log once it exceeds this size. */
    private const val MAX_FILE_BYTES = 512L * 1024L

    private const val LOG_DIR = "Android"
    private const val LOG_NAME = "scene-log.txt"
    private const val PREVIOUS_SUFFIX = ".1"

    // -- State ---------------------------------------------------------------

    private val timestampFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** Ring buffer. `CopyOnWriteArrayList` because readers iterate while writers append. */
    private val memory = CopyOnWriteArrayList<Record>()

    /** Extra sinks (the floating overlay registers one). */
    private val listeners = CopyOnWriteArrayList<(Record) -> Unit>()

    @Volatile
    private var fileLoggingEnabled = false

    @Volatile
    private var appContext: Context? = null

    /** Minimum level written to disk; in-memory and logcat are not filtered by this. */
    @Volatile
    var minimumFileLevel: Int = DEBUG

    data class Record(
        val timestamp: Long,
        val level: Int,
        val tag: String,
        val message: String,
        val throwable: Throwable?
    ) {
        /** `09-24 20:41:07.123 I Scene:Net: connected` */
        fun format(formatter: SimpleDateFormat = timestampFormat): String {
            val levelChar = LEVEL_CHAR[level] ?: "?"
            val trace = throwable?.let { "\n" + Log.getStackTraceString(it) } ?: ""
            return "${formatter.format(Date(timestamp))} $levelChar $TAG_PREFIX:$tag: $message$trace"
        }
    }

    // -- Setup ---------------------------------------------------------------

    /**
     * Attaches the application context (needed to locate the log file) and, when the
     * user has previously enabled the debug layer, resumes file logging.
     *
     * Call once from `Application.onCreate`.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        fileLoggingEnabled = runCatching {
            context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
                .getBoolean(SpfConfig.GLOBAL_SPF_SCENE_LOG, false)
        }.getOrDefault(false)
    }

    /** Enables or disables writing to the log file at runtime. */
    fun setFileLoggingEnabled(enabled: Boolean) {
        fileLoggingEnabled = enabled
        i("Log", if (enabled) "file logging enabled" else "file logging disabled")
    }

    val isFileLoggingEnabled: Boolean get() = fileLoggingEnabled

    /** Registers a sink (e.g. the floating debug overlay). Returns a deregistration handle. */
    fun addListener(listener: (Record) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun removeAllListeners() {
        listeners.clear()
    }

    // -- Logging API ---------------------------------------------------------

    fun v(tag: String, message: String) = log(VERBOSE, tag, message, null)
    fun d(tag: String, message: String) = log(DEBUG, tag, message, null)
    fun i(tag: String, message: String) = log(INFO, tag, message, null)
    fun w(tag: String, message: String) = log(WARN, tag, message, null)
    fun e(tag: String, message: String) = log(ERROR, tag, message, null)

    fun w(tag: String, message: String, throwable: Throwable) = log(WARN, tag, message, throwable)
    fun e(tag: String, message: String, throwable: Throwable) = log(ERROR, tag, message, throwable)

    /**
     * Records the outcome of one named test. The USB harness reads these back, so the
     * format is deliberately rigid: `SCENE_TEST <status> <feature> - <detail>`.
     */
    fun testResult(feature: String, passed: Boolean, detail: String = "") {
        val status = if (passed) "PASS" else "FAIL"
        log(if (passed) INFO else ERROR, "Test", "$TEST_MARKER $status $feature - $detail", null)
    }

    /**
     * Runs [block], logging entry, exit, duration and any thrown exception. Returns
     * the block's value, or null when it threw.
     *
     * This is the intended wrapper for the root/shell operations that are hardest to
     * debug from a stack trace alone, because their failures surface as empty strings
     * rather than exceptions.
     */
    fun <T> trace(tag: String, what: String, block: () -> T): T? {
        val started = System.currentTimeMillis()
        d(tag, "-> $what")
        return try {
            val result = block()
            d(tag, "<- $what (${System.currentTimeMillis() - started}ms) = $result")
            result
        } catch (ex: Throwable) {
            e(tag, "<- $what failed after ${System.currentTimeMillis() - started}ms", ex)
            null
        }
    }

    // -- Reading -------------------------------------------------------------

    /** Snapshot of the in-memory buffer, oldest first. */
    fun recent(limit: Int = MAX_MEMORY_RECORDS): List<Record> = memory.takeLast(limit)

    /** The in-memory buffer rendered as text, ready to paste into a report. */
    fun dump(limit: Int = MAX_MEMORY_RECORDS): String =
        recent(limit).joinToString("\n") { it.format() }

    /** Absolute path of the on-disk log, or null if unavailable. */
    fun logFilePath(): String? = appContext?.let { file(it).absolutePath }

    fun clear() {
        memory.clear()
    }

    // -- Internals -----------------------------------------------------------

    private fun log(level: Int, tag: String, message: String, throwable: Throwable?) {
        val record = Record(System.currentTimeMillis(), level, tag, message, throwable)

        // 1. logcat, so `adb logcat -s Scene*` shows everything live.
        runCatching {
            val logTag = "$TAG_PREFIX:$tag"
            if (throwable != null) {
                Log.println(level, logTag, "$message\n${Log.getStackTraceString(throwable)}")
            } else {
                Log.println(level, logTag, message)
            }
        }

        // 2. Bounded in-memory ring.
        runCatching {
            memory.add(record)
            while (memory.size > MAX_MEMORY_RECORDS) {
                memory.removeAt(0)
            }
        }

        // 3. Sinks (overlay).
        runCatching {
            for (listener in listeners) {
                listener(record)
            }
        }

        // 4. Disk, only when enabled and at/above the file threshold.
        if (fileLoggingEnabled && level >= minimumFileLevel) {
            appendToFile(record)
        }
    }

    private fun file(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "$LOG_DIR/$LOG_NAME")

    private fun appendToFile(record: Record) {
        val context = appContext ?: return
        runCatching {
            val target = file(context)
            target.parentFile?.mkdirs()

            // Rotate before writing so the live file never exceeds the cap by much.
            if (target.exists() && target.length() > MAX_FILE_BYTES) {
                val previous = File(target.absolutePath + PREVIOUS_SUFFIX)
                if (previous.exists()) {
                    previous.delete()
                }
                target.renameTo(previous)
            }

            target.appendText(record.format() + "\n")
        }
    }
}
