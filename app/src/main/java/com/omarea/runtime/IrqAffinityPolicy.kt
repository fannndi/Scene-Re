package com.omarea.runtime

/**
 * Pure IRQ-affinity planning for the Qualcomm balancer (unit-tested).
 *
 * Device evidence (POCO X3 NFC / sm6150, MIUI 12 — `docs/IRQ-AFFINITY.md`):
 *  - `/proc/interrupts` first column is the **virq** (what
 *    `/proc/irq/<n>/smp_affinity_list` accepts), while the number after the
 *    chip name is the **hwirq** — and `msm_irqbalance`'s `IGNORED_IRQ` list
 *    wants hwirqs. The popular IRQ-Balancer module writes virqs, so the
 *    daemon logs `Cannot find matching virq for hwirq(...)` and keeps
 *    overriding the pin — the conf rewrite silently does nothing.
 *  - With the hwirq list the daemon really ignores the IRQs and affinity
 *    writes stick (verified: kgsl=6/msm_drm=7 held for 150 s+, counts moved).
 *
 * Responsibility: parse + build the conf text, validate CPU lists.
 * Non-goals: shell execution ([IrqAffinity] owns that).
 */
object IrqAffinityPolicy {

    data class Irq(val name: String, val virq: Int, val hwirq: Int)

    /** IRQ names we manage on this SoC (line name -> tuning.json key). */
    val TARGETS: Map<String, String> = linkedMapOf(
        "kgsl-3d0" to "kgsl",
        "msm_drm" to "msm_drm"
    )

    /**
     * Parses `/proc/interrupts` for [names]. A line looks like
     * `383:  10305 ... PDC-GIC 332 Level     kgsl-3d0` — virq is the first
     * token minus `:`, hwirq is the third token from the end (chip, hwirq,
     * type, name). Lines without the numeric layout are skipped.
     */
    fun parseInterrupts(text: String, names: Collection<String> = TARGETS.keys): Map<String, Irq> {
        val wanted = names.toSet()
        val result = LinkedHashMap<String, Irq>()
        for (line in text.lines()) {
            val tokens = line.trim().split(Regex("\\s+"))
            if (tokens.size < 5) continue
            val name = tokens.last()
            if (name !in wanted || result.containsKey(name)) continue
            val virq = tokens[0].removeSuffix(":").toIntOrNull() ?: continue
            val hwirq = tokens[tokens.size - 3].toIntOrNull() ?: continue
            result[name] = Irq(name, virq, hwirq)
        }
        return result
    }

    /**
     * Adds [hwirqs] to the `IGNORED_IRQ` line of a msm_irqbalance.conf,
     * preserving every other line. A missing line is appended. Duplicates are
     * not added twice; order is kept stable.
     */
    fun extendIgnoredIrq(conf: String, hwirqs: Collection<Int>): String {
        val extra = hwirqs.filter { it > 0 }
        val lines = conf.lines().toMutableList()
        var found = false
        for (i in lines.indices) {
            val trimmed = lines[i].trim()
            if (!trimmed.startsWith("IGNORED_IRQ")) continue
            found = true
            val eq = lines[i].indexOf('=')
            if (eq < 0) continue
            val existing = lines[i].substring(eq + 1)
                .split(',')
                .mapNotNull { it.trim().toIntOrNull() }
            val merged = (existing + extra).distinct()
            lines[i] = "IGNORED_IRQ=" + merged.joinToString(",")
            break
        }
        if (!found) {
            lines += "IGNORED_IRQ=" + extra.distinct().joinToString(",")
        }
        return lines.joinToString("\n")
    }

    /** `smp_affinity_list` syntax: `6`, `6-7`, `0,4-5` … */
    fun isValidCpuList(value: String): Boolean =
        value.trim().matches(Regex("[0-9]+(-[0-9]+)?(,[0-9]+(-[0-9]+)?)*"))
}
