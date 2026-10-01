package com.omarea.util.measure

import android.content.Context
import com.omarea.util.BatteryCapacity

/**
 * Design battery capacity (read-only).
 *
 * The drain percentages are only as accurate as the capacity they divide by,
 * so the kernel's own `charge_full_design` node is preferred over the
 * power-profile value (`BatteryCapacity`), which is a ROM-declared constant
 * and can be wrong or missing. Both sources are recorded in the benchmark
 * meta and diagnostics so the number is auditable.
 *
 * Responsibility: resolve + parse the design capacity.
 * Non-goals: writing `charge_full` (removed — charging is read-only).
 */
object DesignCapacity {
    private val NODES = listOf(
        "/sys/class/power_supply/bms/charge_full_design",
        "/sys/class/power_supply/battery/charge_full_design"
    )

    /**
     * Kernel value -> mAh. Accepts µAh (typical: `5160000`) and mAh
     * (`5160`); rejects anything outside 2000..20000 mAh as implausible.
     */
    fun parse(raw: String?): Int? {
        val value = raw?.trim()?.toDoubleOrNull() ?: return null
        if (value <= 0) return null
        val mah = if (value >= 1_000_000) value / 1000.0 else value
        val rounded = mah.toInt()
        return if (rounded in 2000..20000) rounded else null
    }

    /** From the kernel node; null when unreadable/implausible. */
    fun read(): Int? = NODES.firstNotNullOfOrNull { parse(SysReader.readFirst(it)) }

    /** Kernel node first, power-profile fallback; 0 when neither works. */
    fun resolve(context: Context): Pair<Int, String> {
        read()?.let { return it to "charge_full_design" }
        val profile = runCatching { BatteryCapacity().getBatteryCapacity(context).toInt() }
            .getOrDefault(0)
            .takeIf { it in 2000..20000 } ?: 0
        return profile to if (profile > 0) "power_profile" else "unknown"
    }
}
