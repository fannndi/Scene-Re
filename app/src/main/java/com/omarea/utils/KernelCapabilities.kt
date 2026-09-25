package com.omarea.utils

import android.content.Context
import com.omarea.common.shared.FileWrite
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.ShellEscape
import com.omarea.utils.SceneLog

/**
 * Single source of truth for what the running kernel actually exposes.
 *
 * The probe (addin/kernel_probe.sh) is read-only and prints `key=value` rows;
 * the result is cached for a minute. Feature code asks [supported] before
 * offering a toggle, so an option the kernel cannot back is never shown, and
 * the Kernel Features dialog / Diagnostics render [report].
 */
object KernelCapabilities {
    private const val CACHE_MS = 60_000L

    @Volatile
    private var cached: LinkedHashMap<String, String> = LinkedHashMap()

    @Volatile
    private var cachedAt = 0L

    @Volatile
    private var scriptPath: String? = null

    private fun script(context: Context): String? {
        scriptPath?.let { return it }
        return try {
            FileWrite.writePrivateShellFile("addin/kernel_probe.sh", "addin/kernel_probe.sh", context)
                .also { scriptPath = it }
        } catch (ex: Exception) {
            SceneLog.e("KernelCapabilities", "failed to extract probe", ex)
            null
        }
    }

    fun probe(context: Context, force: Boolean = false): LinkedHashMap<String, String> {
        val now = System.currentTimeMillis()
        if (!force && cached.isNotEmpty() && now - cachedAt < CACHE_MS) {
            return cached
        }
        val path = script(context) ?: return cached
        val output = KeepShellPublic.doCmdSync("sh " + ShellEscape.quote(path))
        val map = LinkedHashMap<String, String>()
        output.lineSequence().forEach { line ->
            val index = line.indexOf('=')
            if (index > 0) {
                val key = line.substring(0, index).trim()
                if (key.isNotEmpty()) {
                    map[key] = line.substring(index + 1).trim()
                }
            }
        }
        if (map.isNotEmpty()) {
            cached = map
            cachedAt = now
        }
        return map
    }

    fun refresh(context: Context) {
        probe(context, true)
    }

    /** True when the probe reported the key as present ("1"). */
    fun supported(context: Context, key: String): Boolean = probe(context)[key] == "1"

    fun value(context: Context, key: String, fallback: String = ""): String =
        probe(context)[key]?.takeIf { it.isNotEmpty() } ?: fallback

    private val sections = listOf(
        "system" to "Device",
        "cpu" to "CPU / scheduler",
        "gpu" to "GPU (kgsl)",
        "bus" to "Bus / DRAM",
        "io" to "I/O / storage",
        "ufs" to "UFS",
        "thermal" to "Thermal",
        "battery" to "Battery / charging",
        "xiaomi" to "Xiaomi extras",
        "mem" to "Memory"
    )

    /** Grouped, human readable report for the dialog and Diagnostics. */
    fun report(context: Context, force: Boolean = false): String {
        val map = probe(context, force)
        if (map.isEmpty()) {
            return "Kernel probe unavailable."
        }
        val sb = StringBuilder()
        sections.forEach { (prefix, title) ->
            val rows = map.filterKeys { it.startsWith("$prefix.") }
            if (rows.isEmpty()) {
                return@forEach
            }
            sb.append('[').append(title).append("]\n")
            rows.forEach { (key, value) ->
                sb.append("  ").append(key.substringAfter('.')).append(": ")
                    .append(value.ifEmpty { "-" }).append('\n')
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }
}
