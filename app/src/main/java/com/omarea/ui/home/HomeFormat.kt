package com.omarea.ui.home

import java.util.Locale

/**
 * Pure formatting for the Home profile card.
 *
 * Every value the card shows must be explainable: the live kernel range, the
 * requested profile range and a marker when the kernel thermal mitigation is
 * holding the cap below the request (see `docs/PROFILE-ENGINE.md`).
 *
 * Responsibility: numbers → display strings.
 * Non-goals: reading nodes, JSON parsing.
 */
object HomeFormat {

    /** 1804800 → "1804.8"; 300000 → "300"; null/0 → "--". */
    fun mhz(khz: Long?): String {
        if (khz == null || khz <= 0) return "--"
        val value = khz / 1000.0
        return if (value % 1.0 == 0.0) {
            value.toLong().toString()
        } else {
            String.format(Locale.US, "%.1f", value)
        }
    }

    /**
     * CPU cluster row (value text of the profile card):
     *
     *     schedutil
     *     cur 979 MHz · live 300–1209
     *     profil 300–2208 MHz · kernel thermal
     *
     * The profile line (and the thermal marker) is omitted when no profile
     * range is known (engine OFF / global default). `kernelHeld` is true when
     * the live cap sits below the requested profile max.
     */
    fun cluster(
        governor: String,
        curKhz: Long?,
        liveMinKhz: Long?,
        liveMaxKhz: Long?,
        profileMinKhz: Long?,
        profileMaxKhz: Long?,
        kernelHeld: Boolean
    ): String {
        val gov = governor.ifEmpty { "?" }
        val live = if (liveMinKhz != null && liveMaxKhz != null) {
            "${mhz(liveMinKhz)}–${mhz(liveMaxKhz)}"
        } else {
            "--"
        }
        val sb = StringBuilder(gov)
        sb.append('\n').append("cur ").append(mhz(curKhz)).append(" MHz · live ").append(live)
        if (profileMinKhz != null || profileMaxKhz != null) {
            sb.append('\n').append("profil ")
                .append(mhz(profileMinKhz)).append('–').append(mhz(profileMaxKhz)).append(" MHz")
            if (kernelHeld) {
                sb.append(" · kernel thermal")
            }
        }
        return sb.toString()
    }

    /**
     * GPU row: governor (+ requested profile range) and the live frequency.
     *
     *     msm-adreno-tz · profil 180–267 MHz
     *     355 MHz  (180 - 800 MHz)
     */
    fun gpuDetail(
        governor: String,
        curText: String,
        liveRangeText: String,
        profileRangeMhz: String?
    ): String {
        val gov = governor.ifEmpty { "?" }
        val head = if (profileRangeMhz.isNullOrEmpty()) gov else "$gov · profil $profileRangeMhz MHz"
        val body = if (liveRangeText.isEmpty()) curText else "$curText  ($liveRangeText)"
        return head + "\n" + body
    }

    /**
     * Profile GPU range from power levels: index 0 is the highest clock.
     * [floorPwrlevel] is the deepest allowed level, [capPwrlevel] the perf
     * cap (0 = no cap → highest clock). Returns e.g. "180–267" or null when
     * the frequency table is missing.
     */
    fun pwrlevelRangeMhz(tableMhz: List<String>, floorPwrlevel: Int?, capPwrlevel: Int?): String? {
        if (tableMhz.isEmpty()) return null
        val floor = floorPwrlevel ?: (tableMhz.size - 1)
        val cap = capPwrlevel ?: 0
        val floorMhz = tableMhz.getOrNull(floor)?.trim()?.toIntOrNull() ?: return null
        val capMhz = tableMhz.getOrNull(cap)?.trim()?.toIntOrNull() ?: return null
        return if (capMhz == floorMhz) {
            capMhz.toString()
        } else {
            val lo = minOf(capMhz, floorMhz)
            val hi = maxOf(capMhz, floorMhz)
            "$lo–$hi"
        }
    }
}
