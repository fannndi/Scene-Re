package com.omarea.core.profile

/**
 * Decides which read-back mismatches are acceptable.
 *
 * Kernel thermal mitigation can hold `scaling_max_freq` below the requested
 * value (cooling device `thermal-cpufreq-*` active). That is protection, not
 * a failed write: the value will apply on a later cool apply.
 *
 * Responsibility: classify verification mismatches (pure, unit-tested).
 */
object VerifyPolicy {

    /** True when a lower-than-requested max is thermal mitigation. */
    fun isAcceptedMismatch(node: String, wanted: String, live: String): Boolean {
        if (!node.endsWith("scaling_max_freq")) return false
        val wantedFreq = wanted.toLongOrNull() ?: return false
        val liveFreq = live.toLongOrNull() ?: return false
        return liveFreq < wantedFreq
    }
}
