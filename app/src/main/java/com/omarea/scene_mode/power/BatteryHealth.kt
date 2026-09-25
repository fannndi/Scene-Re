package com.omarea.scene_mode.power

import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.ShellEscape
import java.util.Locale

/**
 * Read-only battery health snapshot from the kernel power_supply nodes.
 * Shown in the charge screen dialog and included in the Diagnostics bundle.
 * Every node is optional: missing values are reported as "-".
 *
 * All fields are read in one shell round trip instead of a cat per value.
 */
object BatteryHealth {
    data class Info(
        val capacity: Int,
        val temperatureC: Double,
        val cycles: Int,
        val chargeFullMah: Int,
        val designMah: Int,
        val healthPercent: Int,
        val kernelHealth: String
    )

    private val paths = mapOf(
        "capacity" to "/sys/class/power_supply/battery/capacity",
        "temp" to "/sys/class/power_supply/battery/temp",
        "cycles" to "/sys/class/power_supply/battery/cycle_count",
        "full" to "/sys/class/power_supply/battery/charge_full",
        "design" to "/sys/class/power_supply/battery/charge_full_design",
        "health" to "/sys/class/power_supply/battery/health"
    )

    /** One shell invocation: `key=value` per line, empty when the node is absent. */
    private fun readAll(): Map<String, String> {
        val cmd = paths.entries.joinToString("\n") { (key, path) ->
            "echo $key=\$(cat $path 2> /dev/null)"
        }
        return KeepShellPublic.doCmdSync(cmd)
            .lines()
            .mapNotNull { line ->
                val index = line.indexOf('=')
                if (index > 0) line.substring(0, index).trim() to line.substring(index + 1).trim() else null
            }
            .toMap()
    }

    private fun intValue(values: Map<String, String>, key: String): Int = values[key]?.toIntOrNull() ?: -1

    fun read(): Info {
        val values = readAll()
        val full = intValue(values, "full")
        val design = intValue(values, "design")
        val temp = intValue(values, "temp")
        return Info(
            capacity = intValue(values, "capacity"),
            temperatureC = temp / 10.0,
            cycles = intValue(values, "cycles"),
            chargeFullMah = if (full > 0) full / 1000 else -1,
            designMah = if (design > 0) design / 1000 else -1,
            healthPercent = if (full > 0 && design > 0) full * 100 / design else -1,
            kernelHealth = values["health"].orEmpty()
        )
    }

    fun report(): String {
        val info = read()
        val sb = StringBuilder()
        sb.append("battery health\n")
        sb.append("  level: ").append(if (info.capacity >= 0) "${info.capacity}%" else "-").append('\n')
        sb.append("  temperature: ")
            .append(if (info.temperatureC > 0) String.format(Locale.US, "%.1f C", info.temperatureC) else "-")
            .append('\n')
        sb.append("  cycles: ").append(if (info.cycles >= 0) info.cycles else "-").append('\n')
        sb.append("  charge_full: ")
            .append(if (info.chargeFullMah > 0) "${info.chargeFullMah} mAh" else "-").append('\n')
        sb.append("  charge_full_design: ")
            .append(if (info.designMah > 0) "${info.designMah} mAh" else "-").append('\n')
        sb.append("  health: ").append(if (info.healthPercent > 0) "${info.healthPercent}%" else "-").append('\n')
        sb.append("  kernel health: ").append(info.kernelHealth.ifEmpty { "-" }).append('\n')
        return sb.toString()
    }
}
