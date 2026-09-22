package com.omarea.vtools.kernel

import android.util.Log
import com.omarea.common.shell.KeepShellPublic
import com.omarea.utils.ShellSafety

/**
 * Quote-safe access to kernel sysfs/procfs nodes.
 *
 * Every read and write goes through [KeepShellPublic] so the active privilege tier (root, Shizuku
 * or non-root) is respected automatically. Missing or denied nodes return an empty string instead
 * of throwing, which lets every screen render "unavailable" without special-casing.
 *
 * Ported and adapted from RvKernel-Manager (GPL-3.0, Rve27); libsu calls were replaced by the
 * app's own shell backend.
 */
object KernelShell {
    private const val TAG = "SceneKernel"

    private val NUMERIC_REGEX = Regex("^[0-9]+$")

    /**
     * Values accepted for free-text kernel nodes; excludes shell metacharacters and newlines.
     * `\s` is deliberately not used because it also matches `\n`, which would let a value break
     * out of a single command.
     */
    private val TEXT_REGEX = Regex("^[A-Za-z0-9 \t.,_:=/+%*-]{1,64}$")

    /** First line of a node, trimmed, or an empty string when it is absent or denied. */
    fun read(path: String): String {
        if (!isSafePath(path)) {
            return ""
        }
        return try {
            val output = KeepShellPublic.doCmdSync("cat ${ShellSafety.quote(path)} 2>/dev/null")
            if (output == "error") "" else output.lineSequence().firstOrNull()?.trim().orEmpty()
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to read ${path}: ${ex.message}")
            ""
        }
    }

    /** Full content of a node, trimmed, or an empty string when it is absent or denied. */
    fun readAll(path: String): String {
        if (!isSafePath(path)) {
            return ""
        }
        return try {
            val output = KeepShellPublic.doCmdSync("cat ${ShellSafety.quote(path)} 2>/dev/null")
            if (output == "error") "" else output.trim()
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to read ${path}: ${ex.message}")
            ""
        }
    }

    /**
     * Reads many nodes in a single shell round trip.
     *
     * This matters on the SoC screen, which inspects dozens of sysfs entries: one command per node
     * would serialize a shell round trip per value and make the screen visibly slow.
     */
    fun readMany(paths: List<String>): Map<String, String> {
        val readable = paths.filter { isSafePath(it) }
        if (readable.isEmpty()) {
            return emptyMap()
        }
        val script = buildString {
            readable.forEachIndexed { index, path ->
                append("echo \"@@KP").append(index).append("@@\$(cat ")
                append(ShellSafety.quote(path))
                append(" 2>/dev/null)\"\n")
            }
        }
        val values = HashMap<String, String>(readable.size)
        try {
            val output = KeepShellPublic.doCmdSync(script)
            if (output == "error") {
                return emptyMap()
            }
            for (line in output.lines()) {
                val marker = line.indexOf("@@KP")
                if (marker < 0) {
                    continue
                }
                val end = line.indexOf("@@", marker + 4)
                if (end < 0) {
                    continue
                }
                val index = line.substring(marker + 4, end).toIntOrNull() ?: continue
                if (index in readable.indices) {
                    values[readable[index]] = line.substring(end + 2).trim()
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to read kernel nodes: ${ex.message}")
        }
        return values
    }

    /** Writes a value and verifies it by reading the node back. */
    fun write(path: String, value: String): Boolean {
        if (!isSafePath(path) || value.isEmpty() || value.contains('\n')) {
            return false
        }
        return try {
            KeepShellPublic.doCmdSync("echo ${ShellSafety.quote(value)} > ${ShellSafety.quote(path)} 2>/dev/null")
            read(path) == value
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to write ${path}: ${ex.message}")
            false
        }
    }

    /**
     * Runs a multi-step script and returns true when the last `cat` of [verifyPath] matches
     * [expected]. Used for sequences such as swapoff/reset/mkswap/swapon on zram.
     */
    fun writeSequence(script: String, verifyPath: String, expected: String): Boolean {
        return try {
            KeepShellPublic.doCmdSync(script)
            read(verifyPath) == expected
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to run kernel sequence: ${ex.message}")
            false
        }
    }

    /** True when the node can be read (exists and this tier is allowed to see it). */
    fun isAvailable(path: String): Boolean = read(path).isNotEmpty()

    /** First readable path of [candidates], or null when none exists. */
    fun firstAvailable(candidates: List<String>): String? {
        for (candidate in candidates) {
            if (isAvailable(candidate)) {
                return candidate
            }
        }
        return null
    }

    fun isNumeric(value: String): Boolean = NUMERIC_REGEX.matches(value)

    /** Rejects values that could alter the shell command instead of the kernel node. */
    fun isSafeTextValue(value: String): Boolean = TEXT_REGEX.matches(value)

    /**
     * Node paths are compile-time constants, but they still reach a root shell; reject anything
     * that is not a plain absolute path.
     */
    fun isSafePath(path: String): Boolean {
        if (!path.startsWith("/") || path.length > 256 || path.contains("..")) {
            return false
        }
        return path.none { character ->
            character.isWhitespace() || character == '\'' || character == '"' || character == '$' ||
                    character == '`' || character == ';' || character == '&' || character == '|' ||
                    character == '<' || character == '>' || character == '(' || character == ')'
        }
    }

    /** Formats a byte count as GB/MB/KB. */
    fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024L * 1024 -> "%.0f MB".format(bytes / (1024.0 * 1024))
            bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
