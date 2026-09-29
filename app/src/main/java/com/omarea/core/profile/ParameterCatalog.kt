package com.omarea.core.profile

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Generates Parameter.sh — the human/LLM-readable catalog of every tunable
 * parameter: JSON path, stock value, allowed values (from [DeviceCaps]) and
 * the value each profile sets.
 *
 * Pure Kotlin: unit-tested without a device.
 * Responsibility: rendering the catalog text.
 * Non-goals: reading/writing files (see [TuningRepository]).
 */
object ParameterCatalog {

    private val FREQ_PARAM = Regex("""cpu\.policy\d+\.(min|max|hispeed)$""")
    private val GOV_PARAM = Regex("""cpu\.policy\d+\.governor$""")
    private val POLICY = Regex("""policy\d+""")

    fun generate(platform: String, json: JSONObject, caps: DeviceCaps): String {
        val profiles = json.optJSONObject("profiles") ?: JSONObject()
        val stock = ProfileKey.profile(profiles, ProfileKey.RELEASE)

        val perProfile = HashMap<String, HashMap<String, String>>()
        for (mode in ProfileKey.ALL_WITH_RELEASE) {
            val flat = HashMap<String, String>()
            flatten(ProfileKey.profile(profiles, mode), "", flat)
            perProfile[mode] = flat
        }
        val stockFlat = HashMap<String, String>()
        flatten(stock, "", stockFlat)

        val allPaths = LinkedHashSet<String>()
        for (flat in perProfile.values) allPaths.addAll(flat.keys)
        allPaths.addAll(stockFlat.keys)

        val sb = StringBuilder()
        sb.appendLine("# ====================================================================")
        sb.appendLine("# Scene Parameter.sh — tunable parameter catalog (auto-generated)")
        sb.appendLine("# platform: $platform   generated: " +
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
        sb.appendLine("#")
        sb.appendLine("# Every block documents one tunable: JSON path, stock value, allowed")
        sb.appendLine("# values (read from the device) and the value each profile sets.")
        sb.appendLine("# Edit <platform>.tuning.json to override — applied on mode switch.")
        sb.appendLine("# ====================================================================")
        sb.appendLine()

        for (path in allPaths) {
            val policy = POLICY.find(path)?.value
            val allowed = when {
                GOV_PARAM.matches(path) && policy != null ->
                    caps.governors[policy]?.joinToString(",") ?: ""
                FREQ_PARAM.matches(path) && policy != null -> {
                    val freqs = caps.freqs[policy] ?: emptyList()
                    "${freqs.firstOrNull() ?: "?"}..${freqs.lastOrNull() ?: "?"} KHz"
                }
                path.contains("cores_online") -> "0, 1"
                path.endsWith("thermal_sconfig") -> "0..7 (MIUI thermal profiles)"
                path.endsWith("renderer") -> "default, opengl, skiagl, skiavk"
                path.endsWith("vulkan") -> "false, true (needs resetprop + reboot)"
                else -> ""
            }
            sb.append("[$path]")
            if (allowed.isNotEmpty()) sb.append("  allowed: $allowed")
            sb.appendLine()
            sb.appendLine("  stock       : ${stockFlat[path] ?: "-"}")
            for (mode in ProfileKey.ALL_WITH_RELEASE) {
                sb.appendLine("  ${mode.padEnd(12)}: ${perProfile[mode]?.get(path) ?: "-"}")
            }
            sb.appendLine()
        }
        return sb.toString()
    }

    private fun flatten(obj: JSONObject?, prefix: String, into: HashMap<String, String>) {
        obj ?: return
        for (key in obj.keys()) {
            val value = obj.get(key)
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            if (value is JSONObject) flatten(value, path, into) else into[path] = value.toString()
        }
    }
}
