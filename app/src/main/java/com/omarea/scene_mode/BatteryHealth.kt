package com.omarea.scene_mode

import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.ShellEscape
import java.util.Locale

/**
 * Read-only battery health snapshot from the kernel power_supply nodes.
 * Shown in the charge screen dialog and included in the Diagnostics bundle.
 * Every node is optional: missing values are reported as "-".
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

    private fun readInt(path: String): Int =
        KeepShellPublic.doCmdSync("cat " + ShellEscape.quote(path) + " 2> /dev/null")
            .trim().toIntOrNull() ?: -1

    fun read(): Info {
        val cycles = readInt("/sys/class/power_supply/battery/cycle_count")
        val full = readInt("/sys/class/power_supply/battery/charge_full")
        val design = readInt("/sys/class/power_supply/battery/charge_full_design")
        val healthPercent = if (full > 0 && design > 0) full * 100 / design else -1
        val temp = readInt("/sys/class/power_supply/battery/temp")
        val capacity = readInt("/sys/class/power_supply/battery/capacity")
        val kernelHealth = KeepShellPublic
            .doCmdSync("cat /sys/class/power_supply/battery/health 2> /dev/null").trim()
        return Info(
            capacity = capacity,
            temperatureC = temp / 10.0,
            cycles = cycles,
            chargeFullMah = if (full > 0) full / 1000 else -1,
            designMah = if (design > 0) design / 1000 else -1,
            healthPercent = healthPercent,
            kernelHealth = kernelHealth
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
