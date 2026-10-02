package com.omarea.engine

/**
 * Device capabilities probed once per apply: available CPU frequencies and
 * governors per policy, the TCP congestion-control algorithms and the devfreq
 * bus latency domains.
 *
 * Responsibility: capability snapshot + frequency math (pure, unit-tested).
 * Non-goals: choosing values (that is the planner/JSON).
 * Invariants:
 *  - [clampFreq] returns a real OPP whenever the list is non-empty and sorted
 *    ascending (the kernel reports them that way).
 *  - [probeScript]/[parse] keep the whole probe in one root-shell round trip.
 */
data class DeviceCaps(
    val freqs: Map<String, List<Long>>,
    val governors: Map<String, List<String>>,
    /** TCP congestion algorithms the running kernel offers. */
    val tcpCc: List<String> = emptyList(),
    /**
     * Devfreq CPU/bus latency domains -> available frequencies ascending.
     * `bw` vote domains are excluded on purpose: their hwmon governor computes
     * bandwidth votes, pinning them would fight the driver.
     */
    val devfreqLatency: Map<String, List<Long>> = emptyMap()
) {
    companion object {
        /** Policies the engine validates against. */
        val POLICIES = listOf("policy0", "policy6")

        /** Devfreq directory name prefix accepted as a CPU/bus domain. */
        const val DEVFREQ_PREFIX = "soc:qcom,cpu"

        /**
         * One-shot probe: every capability wrapped in a `@@key@@` marker so a
         * single root-shell round trip replaces a read per policy/node.
         */
        fun probeScript(policies: List<String> = POLICIES): String {
            val sb = StringBuilder()
            sb.appendLine("echo \"@@freqs@@\"")
            for (policy in policies) {
                sb.appendLine("echo \"$policy|\$(cat ${ShellNodes.cpufreq(policy)}/scaling_available_frequencies 2>/dev/null)\"")
            }
            sb.appendLine("echo \"@@governors@@\"")
            for (policy in policies) {
                sb.appendLine("echo \"$policy|\$(cat ${ShellNodes.cpufreq(policy)}/scaling_available_governors 2>/dev/null)\"")
            }
            sb.appendLine("echo \"@@tcpcc@@\"")
            sb.appendLine("cat ${ShellNodes.TCP_AVAILABLE_CC} 2>/dev/null")
            sb.appendLine("echo \"@@devfreq@@\"")
            sb.appendLine("for d in ${ShellNodes.DEVFREQ}/*; do")
            sb.appendLine("  n=\${d##*/}")
            sb.appendLine("  case \"\$n\" in")
            sb.appendLine("    $DEVFREQ_PREFIX*lat|$DEVFREQ_PREFIX*latfloor)")
            sb.appendLine("      echo \"\$n|\$(cat \"\$d/available_frequencies\" 2>/dev/null)\"")
            sb.appendLine("      ;;")
            sb.appendLine("  esac")
            sb.appendLine("done")
            return sb.toString()
        }

        /** Pure parser for [probeScript] output. */
        fun parse(output: String): DeviceCaps {
            val sections = TweakCommands.parseSections(output)

            val freqs = LinkedHashMap<String, List<Long>>()
            sections["freqs"].orEmpty().lines().forEach { line ->
                val parts = line.split('|', limit = 2)
                if (parts.size == 2) freqs[parts[0].trim()] = parseFreqList(parts[1])
            }

            val governors = LinkedHashMap<String, List<String>>()
            sections["governors"].orEmpty().lines().forEach { line ->
                val parts = line.split('|', limit = 2)
                if (parts.size == 2) {
                    governors[parts[0].trim()] = parts[1]
                        .split(Regex("\\s+"))
                        .filter { it.isNotEmpty() }
                }
            }

            val tcpCc = sections["tcpcc"].orEmpty()
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }

            val devfreq = LinkedHashMap<String, List<Long>>()
            sections["devfreq"].orEmpty().lines().forEach { line ->
                val parts = line.split('|', limit = 2)
                if (parts.size == 2) {
                    val list = parseFreqList(parts[1])
                    if (list.isNotEmpty()) devfreq[parts[0].trim()] = list
                }
            }

            return DeviceCaps(freqs, governors, tcpCc, devfreq)
        }

        /** Runs the batch probe and returns the parsed capabilities. */
        fun read(policies: List<String> = POLICIES): DeviceCaps =
            parse(RootShell.run(probeScript(policies)))

        fun isGovernorAvailable(name: String, available: List<String>): Boolean =
            available.isEmpty() || available.contains(name)

        /**
         * Nearest available OPP; the request itself when [available] is empty.
         * Ties resolve to the lower OPP (first hit while scanning ascending).
         */
        fun clampFreq(requested: Long, available: List<Long>): Long {
            if (available.isEmpty()) return requested
            var best = available[0]
            var bestDistance = kotlin.math.abs(best - requested)
            for (index in 1 until available.size) {
                val candidate = available[index]
                val distance = kotlin.math.abs(candidate - requested)
                if (distance < bestDistance) {
                    best = candidate
                    bestDistance = distance
                }
            }
            return best
        }

        /**
         * Middle OPP of an ascending list (Encore's `which_midfreq`: the
         * element at `size - (size+1)/2`, i.e. the upper middle for even
         * counts). Null when the list is empty.
         */
        fun midFreq(available: List<Long>): Long? {
            if (available.isEmpty()) return null
            return available[available.size - (available.size + 1) / 2]
        }

        /** First algorithm from [preferred] the kernel offers, or null. */
        fun firstAvailableCc(preferred: List<String>, available: List<String>): String? =
            preferred.firstOrNull { it in available }

        private fun parseFreqList(text: String): List<Long> =
            text.split(Regex("\\s+"))
                .mapNotNull { it.toLongOrNull() }
                .sorted()
    }
}
