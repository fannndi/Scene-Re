package com.omarea.library.shell

/**
 * Shared formatting for kernel clock values, which are always reported in kHz
 * but are far more readable in MHz.
 *
 * [cpuKhzToMhz] and [gpuKhzToMhz] only differ in how many trailing digits they
 * strip (CPU rates are 6-digit kHz, older GPU nodes may pad further), so they
 * share one implementation and are kept as two named entry points for clarity
 * at the call sites.
 */
object FreqFormatter {
    /** Trailing digits stripped to go from a CPU kHz string to MHz. */
    private const val CPU_KHZ_SUFFIX = 3

    /** Trailing digits stripped to go from a GPU kHz string to MHz. */
    private const val GPU_KHZ_SUFFIX = 6

    /** `"1804800"` -> `"1804"`. Returns `""` for null, `"0"` for an empty-ish value. */
    @JvmStatic
    fun cpuKhzToMhz(freq: String?): String {
        if (freq == null) return ""
        return when {
            freq.length > CPU_KHZ_SUFFIX -> freq.substring(0, freq.length - CPU_KHZ_SUFFIX)
            freq.isEmpty() -> "0"
            else -> freq
        }
    }

    /** `"1804800"` -> `"1804"` (GPU nodes report 6-digit kHz). */
    @JvmStatic
    fun gpuKhzToMhz(freq: String?): String {
        if (freq.isNullOrEmpty()) return ""
        return if (freq.length > GPU_KHZ_SUFFIX) {
            freq.substring(0, freq.length - GPU_KHZ_SUFFIX)
        } else {
            freq
        }
    }

    /** Like [cpuKhzToMhz] but appends the unit, e.g. `"1804 Mhz"`. */
    @JvmStatic
    fun cpuKhzToMhzWithUnit(freq: String): String {
        return if (freq.length > CPU_KHZ_SUFFIX) {
            freq.substring(0, freq.length - CPU_KHZ_SUFFIX) + " Mhz"
        } else {
            freq
        }
    }

    /** Like [gpuKhzToMhz] but appends the unit, e.g. `"1804 Mhz"`. */
    @JvmStatic
    fun gpuKhzToMhzWithUnit(freq: String): String {
        if (freq.isEmpty()) return ""
        return if (freq.length > GPU_KHZ_SUFFIX) {
            freq.substring(0, freq.length - GPU_KHZ_SUFFIX) + " Mhz"
        } else {
            freq
        }
    }
}
