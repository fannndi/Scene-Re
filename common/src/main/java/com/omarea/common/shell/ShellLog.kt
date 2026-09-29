package com.omarea.common.shell

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Ring buffer of shell executions, meant for diagnostics / LLM-readable debug reports.
 * Every command executed through [KeepShell] gets captured here (command + trimmed output).
 */
object ShellLog {
    private const val MAX_ENTRIES = 400
    private const val MAX_CMD_LEN = 300
    private const val MAX_OUT_LEN = 500

    private val buffer = ArrayDeque<String>(MAX_ENTRIES)
    private val format = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** Disable to stop capturing (e.g. to skip noisy sections from reports). */
    @Volatile
    var enabled: Boolean = true

    fun log(cmd: String, output: String, error: Boolean = false) {
        if (!enabled) return
        synchronized(buffer) {
            val ts = format.format(Date())
            val oneLine = cmd.replace("\n", " ; ").trim()
            val cmdPart = if (oneLine.length > MAX_CMD_LEN) oneLine.take(MAX_CMD_LEN) + "…" else oneLine
            val line = buildString {
                append(ts)
                append(if (error) " [ERR] $ " else " $ ")
                append(cmdPart)
                val out = output.replace("\n", " ⏎ ").trim()
                if (out.isNotEmpty()) {
                    append("\n  → ")
                    append(if (out.length > MAX_OUT_LEN) out.take(MAX_OUT_LEN) + "…" else out)
                }
            }
            if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(line)
        }
    }

    /** Newest last. */
    fun dump(): String = synchronized(buffer) { buffer.joinToString("\n") }

    fun size(): Int = synchronized(buffer) { buffer.size }

    fun clear() = synchronized(buffer) { buffer.clear() }
}
