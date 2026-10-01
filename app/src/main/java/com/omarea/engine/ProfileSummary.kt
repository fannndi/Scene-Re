package com.omarea.engine

import org.json.JSONObject
import java.util.Locale

/**
 * One-line human summary of a profile for the Tuner card rows.
 *
 * Responsibility: pure formatting from a profile JSON object.
 * Non-goals: knowing which values are good, reading live kernel state.
 */
object ProfileSummary {

    /**
     * "1.32 / 1.32 GHz · UFS save" — CPU caps per policy in [policies] order,
     * followed by the UFS mode when the profile pins one. Missing values are
     * skipped, never rendered as "null".
     */
    fun summarize(profile: JSONObject?, policies: List<String>): String {
        profile ?: return ""
        val cpu = profile.optJSONObject("cpu")
        val caps = policies.mapNotNull { policy ->
            cpu?.optJSONObject(policy)?.optLong("max")?.takeIf { it > 0 }
        }
        val parts = ArrayList<String>()
        if (caps.isNotEmpty()) {
            parts += caps.joinToString(" / ") { ghz(it) } + " GHz"
        }
        profile.optString("ufs").takeIf { it.isNotEmpty() }?.let { parts += "UFS $it" }
        profile.optJSONObject("gpu")?.optString("max_pwrlevel")
            ?.takeIf { it.isNotEmpty() && it != "0" }
            ?.let { parts += "GPU ≤ p$it" }
        return parts.joinToString(" · ")
    }

    /** kHz → "1.32" (two decimals, always). */
    private fun ghz(khz: Long): String =
        String.format(Locale.US, "%.2f", khz / 1_000_000.0)
}
