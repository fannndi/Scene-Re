package com.omarea.util.measure

import com.omarea.common.shell.KeepShellPublic
import java.io.File

/**
 * Time-consistent node reader.
 *
 * Responsibility: read many sysfs/proc nodes with the smallest possible delay
 * between values - direct file reads first (no shell), then ONE batched root
 * shell round trip for the rest. Values that belong to the same "sample" must
 * not be read through separate shell calls (temporal skew).
 *
 * Non-goals: parsing/formatting values (callers own their formats) and caching
 * cadence (callers own their timers).
 */
object SysReader {
    /** Direct read; null when unreadable / empty. */
    fun readDirect(path: String): String? = try {
        val file = File(path)
        if (file.canRead()) file.readText().trim().ifEmpty { null } else null
    } catch (ex: Exception) {
        null
    }

    /**
     * Reads [paths]: direct reads where possible, one batched shell call for
     * the rest. Only paths that produced output are present in the result.
     */
    fun read(paths: Collection<String>): Map<String, String> {
        if (paths.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        val missing = ArrayList<String>()
        for (path in paths) {
            val direct = readDirect(path)
            if (direct != null) result[path] = direct else missing.add(path)
        }
        if (missing.isEmpty()) return result

        val script = buildString {
            for (path in missing) {
                append("if [ -e \"").append(path).append("\" ]; then ")
                append("echo \"@@").append(path).append("@@\"; ")
                append("cat \"").append(path).append("\" 2>/dev/null; fi\n")
            }
        }
        val out = KeepShellPublic.doCmdSync(script)
        var current: String? = null
        val body = StringBuilder()
        for (line in out.split("\n")) {
            val marker = line.startsWith("@@") && line.endsWith("@@") && line.length > 4
            if (marker) {
                current?.let { result[it] = body.toString().trim() }
                current = line.substring(2, line.length - 2)
                body.setLength(0)
            } else if (current != null) {
                if (body.isNotEmpty()) body.append('\n')
                body.append(line)
            }
        }
        current?.let { result[it] = body.toString().trim() }
        return result
    }

    fun read(vararg paths: String): Map<String, String> = read(paths.toList())

    /** First non-null value among [paths] (direct+shell), for fallback chains. */
    fun readFirst(vararg paths: String): String? {
        for (path in paths) {
            readDirect(path)?.let { return it }
        }
        val values = read(paths.toList())
        for (path in paths) values[path]?.let { return it }
        return null
    }
}
