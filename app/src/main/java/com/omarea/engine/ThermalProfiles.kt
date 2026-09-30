package com.omarea.engine

/**
 * MIUI thermal profile table — decoded from the stock ROM's
 * `vendor/etc/thermal-map.conf` (AES-128-CBC, key/IV `thermalopenssl.h`,
 * reference: github mi-thermal-crypt).
 *
 * `sconfig` (`/sys/class/thermal/thermal_message/sconfig`, rw 0664
 * system:system) selects which `thermal-<x>.conf` mi_thermald loads as its
 * active policy. The tuning JSON may set it per profile (`thermal_sconfig`).
 *
 * Important: only presets whose config file actually ships in the ROM are
 * marked [Entry.shipped]. Selecting a non-shipped value (1/2/11/14) makes
 * mi_thermald look for a missing config — avoid them in tuning.
 *
 * Responsibility: sconfig value → config name/label lookup.
 * Non-goals: writing nodes, reading the live value (DiagnosticsCollector).
 */
object ThermalProfiles {

    data class Entry(
        val sconfig: Int,
        val config: String,
        val label: String,
        val shipped: Boolean
    )

    /** Full map, exactly as in the stock thermal-map.conf. */
    val entries: List<Entry> = listOf(
        Entry(0, "thermal-normal.conf", "Normal", true),
        Entry(1, "thermal-high.conf", "High", false),
        Entry(2, "thermal-extreme.conf", "Extreme", false),
        Entry(8, "thermal-phone.conf", "Phone call", true),
        Entry(9, "thermal-tgame.conf", "Game", true),
        Entry(10, "thermal-nolimits.conf", "No limits", true),
        Entry(11, "thermal-class0.conf", "Class 0", false),
        Entry(12, "thermal-camera.conf", "Camera", true),
        Entry(13, "thermal-tgame.conf", "Game", true),
        Entry(14, "thermal-youtube.conf", "YouTube", false),
        Entry(15, "thermal-arvr.conf", "AR/VR", true),
        Entry(16, "thermal-tgame.conf", "Game", true)
    )

    /** Presets safe to use: the config file ships in the retail ROM. */
    val shipped: List<Entry> get() = entries.filter { it.shipped }

    fun find(sconfig: Int): Entry? = entries.firstOrNull { it.sconfig == sconfig }

    /** "0 (Normal)" style label; unknown values pass through as-is. */
    fun label(sconfig: String?): String {
        val value = sconfig?.trim()?.toIntOrNull() ?: return sconfig.orEmpty()
        val entry = find(value) ?: return value.toString()
        return "$value (${entry.label})"
    }

    /** Human list for ParameterCatalog: "0=Normal, 8=Phone call, …". */
    fun presetList(): String =
        shipped.joinToString(", ") { "${it.sconfig}=${it.label}" }
}
