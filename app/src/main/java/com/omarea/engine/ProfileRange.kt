package com.omarea.engine

import org.json.JSONObject

/**
 * Requested CPU/GPU ranges of one profile, straight from the tuning JSON.
 *
 * The Home card shows the live kernel values next to these so a profile can
 * be compared against what the kernel actually enforces (thermal hold, ...).
 * Values are the raw JSON requests (no OPP clamping/formatting).
 *
 * Responsibility: JSON → requested ranges.
 * Non-goals: reading nodes, formatting (UI owns that), applying profiles.
 */
object ProfileRange {

    data class Cpu(val minKhz: Long?, val maxKhz: Long?)

    /** Power-level indices as written by the profile (0 = highest clock). */
    data class Gpu(val floorPwrlevel: Int?, val capPwrlevel: Int?)

    fun cpu(json: JSONObject?, mode: String, policy: String): Cpu? {
        json ?: return null
        val profile = ProfileKey.profile(json.optJSONObject("profiles"), mode) ?: return null
        val cfg = profile.optJSONObject("cpu")?.optJSONObject(policy) ?: return null
        val min = if (cfg.has("min")) cfg.optLong("min") else null
        val max = if (cfg.has("max")) cfg.optLong("max") else null
        if (min == null && max == null) return null
        return Cpu(min, max)
    }

    fun gpu(json: JSONObject?, mode: String): Gpu? {
        json ?: return null
        val profile = ProfileKey.profile(json.optJSONObject("profiles"), mode) ?: return null
        val gpu = profile.optJSONObject("gpu") ?: return null
        val floor = if (gpu.has("min_pwrlevel")) gpu.optInt("min_pwrlevel") else null
        val cap = if (gpu.has("max_pwrlevel")) gpu.optInt("max_pwrlevel") else null
        if (floor == null && cap == null) return null
        return Gpu(floor, cap)
    }
}
