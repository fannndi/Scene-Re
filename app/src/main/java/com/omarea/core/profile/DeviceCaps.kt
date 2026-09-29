package com.omarea.core.profile

import com.omarea.core.shell.RootShell
import com.omarea.core.shell.ShellNodes

/**
 * Device capabilities probed once per apply: available CPU frequencies and
 * governors per policy.
 *
 * Responsibility: capability snapshot + frequency math (pure, unit-tested).
 * Non-goals: choosing values (that is the planner/JSON).
 * Invariants:
 *  - [clampFreq] returns a real OPP whenever the list is non-empty and sorted
 *    ascending (the kernel reports them that way).
 */
data class DeviceCaps(
    val freqs: Map<String, List<Long>>,
    val governors: Map<String, List<String>>
) {
    companion object {
        /** Policies the engine validates against. */
        val POLICIES = listOf("policy0", "policy6")

        fun read(policies: List<String> = POLICIES): DeviceCaps {
            val freqs = LinkedHashMap<String, List<Long>>()
            val governors = LinkedHashMap<String, List<String>>()
            for (policy in policies) {
                val node = ShellNodes.cpufreq(policy)
                freqs[policy] = RootShell.read("$node/scaling_available_frequencies")
                    .split(Regex("\\s+"))
                    .mapNotNull { it.toLongOrNull() }
                    .sorted()
                governors[policy] = RootShell.read("$node/scaling_available_governors")
                    .split(Regex("\\s+"))
                    .filter { it.isNotEmpty() }
            }
            return DeviceCaps(freqs, governors)
        }

        fun isGovernorAvailable(name: String, available: List<String>): Boolean =
            available.isEmpty() || available.contains(name)

        /** Nearest available OPP; the request itself when [available] is empty. */
        fun clampFreq(requested: Long, available: List<Long>): Long {
            if (available.isEmpty()) return requested
            if (available.contains(requested)) return requested
            var best = available.last()
            for (f in available) {
                if (f <= requested) best = f
                if (f >= requested) {
                    best = if (f - requested < requested - best) f else best
                    break
                }
            }
            return best
        }
    }
}
