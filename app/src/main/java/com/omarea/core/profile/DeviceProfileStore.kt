package com.omarea.core.profile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.ShellLog
import com.omarea.vtools.R
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Per-device tuning store.
 *
 *   Bundled default : assets/powercfg/<platform>/tuning.json  (device-exact)
 *   User copy       : /sdcard/Scene/profiles/<platform>.tuning.json
 *                     (editable via VS Code over adb, any file manager, or
 *                     the in-app editor). The user copy wins when present.
 *
 * Also generates Parameter.sh — the human/LLM-readable catalog of every
 * tunable parameter with stock value, allowed values and per-profile values.
 */
object DeviceProfileStore {

    fun dir(): File = File(Environment.getExternalStorageDirectory(), "Scene/profiles")

    fun userTuningFile(platform: String): File = File(dir(), "$platform.tuning.json")

    fun hasUserTuning(platform: String): Boolean = userTuningFile(platform).isFile

    fun logFile(): File = File(dir(), "profile.log")

    /** Returns the effective tuning JSON: user copy → bundled asset copy. */
    fun readTuning(context: Context, platform: String): JSONObject? {
        try {
            val user = userTuningFile(platform)
            if (user.isFile) {
                return JSONObject(user.readText())
            }
            context.assets.open("powercfg/$platform/tuning.json").bufferedReader().use {
                return JSONObject(it.readText())
            }
        } catch (ex: Exception) {
            ShellLog.log("DeviceProfileStore.readTuning", ex.message ?: "error", error = true)
        }
        return null
    }

    fun readUserTuningText(platform: String): String? {
        val f = userTuningFile(platform)
        return if (f.isFile) f.readText() else null
    }

    fun writeUserTuning(platform: String, content: String): Boolean = try {
        dir().mkdirs()
        userTuningFile(platform).writeText(content)
        true
    } catch (ex: Exception) {
        false
    }

    /** Copies the bundled tuning.json to the user folder (only if absent). */
    fun ensureUserCopy(context: Context, platform: String) {
        val user = userTuningFile(platform)
        if (user.isFile) return
        try {
            dir().mkdirs()
            val text = context.assets.open("powercfg/$platform/tuning.json")
                .bufferedReader().use { it.readText() }
            user.writeText(text)
        } catch (_: Exception) {
        }
    }

    fun restoreBundled(context: Context, platform: String): Boolean = try {
        dir().mkdirs()
        val text = context.assets.open("powercfg/$platform/tuning.json")
            .bufferedReader().use { it.readText() }
        userTuningFile(platform).writeText(text)
        true
    } catch (ex: Exception) {
        false
    }

    /** Opens the profiles folder in the system file manager. */
    fun openFolder(context: Context) {
        dir().mkdirs()
        val uri = Uri.parse(
            "content://com.android.externalstorage.documents/document/primary%3AScene%2Fprofiles"
        )
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setClassName("com.google.android.documentsui", "com.android.documentsui.files.FilesActivity")
                    data = uri
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        } catch (ex: Exception) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.parse("file://" + dir().absolutePath), "resource/folder")
                    }
                )
            } catch (_: Exception) {
                com.omarea.common.ui.DialogHelper.helpInfo(
                    context,
                    context.getString(R.string.kernel_profile),
                    context.getString(R.string.kernel_profile_path, dir().absolutePath)
                )
            }
        }
    }

    // -------------------------------------------------------------- catalog
    private fun flatten(obj: JSONObject?, prefix: String, into: HashMap<String, String>) {
        obj ?: return
        for (key in obj.keys()) {
            val v = obj.get(key)
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            if (v is JSONObject) flatten(v, path, into) else into[path] = v.toString()
        }
    }

    /**
     * Generates Parameter.sh — the human/LLM-readable catalog of every
     * tunable parameter: JSON path, stock value, allowed values (read live
     * from the device) and the value each profile currently sets.
     */
    fun writeParameterCatalog(context: Context, platform: String, json: JSONObject) {
        try {
            val keepShell = KeepShellPublic
            val policies = listOf("policy0", "policy6")
            val availFreqs = HashMap<String, String>()
            val availGovs = HashMap<String, String>()
            for (policy in policies) {
                val node = "/sys/devices/system/cpu/cpufreq/$policy"
                availFreqs[policy] = keepShell.doCmdSync("cat $node/scaling_available_frequencies").trim()
                availGovs[policy] = keepShell.doCmdSync("cat $node/scaling_available_governors").trim()
            }

            val profiles = json.optJSONObject("profiles") ?: JSONObject()
            val stock = profiles.optJSONObject("release") ?: JSONObject()

            val perProfile = HashMap<String, HashMap<String, String>>()
            for (mode in ProfileKey.ALL_WITH_RELEASE) {
                val m = HashMap<String, String>()
                flatten(profiles.optJSONObject(mode), "", m)
                perProfile[mode] = m
            }
            val stockFlat = HashMap<String, String>()
            flatten(stock, "", stockFlat)

            val allPaths = LinkedHashSet<String>()
            for (m in perProfile.values) allPaths.addAll(m.keys)
            allPaths.addAll(stockFlat.keys)

            val freqParams = Regex("""cpu\.policy\d+\.(min|max|hispeed)$""")
            val govParams = Regex("""cpu\.policy\d+\.governor$""")

            val sb = StringBuilder()
            sb.appendLine("# ====================================================================")
            sb.appendLine("# Scene Parameter.sh — tunable parameter catalog (auto-generated)")
            sb.appendLine("# platform: $platform   generated: " +
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
            sb.appendLine("#")
            sb.appendLine("# Every block documents one tunable: JSON path, stock value, allowed")
            sb.appendLine("# values (read live from the device) and the value each profile sets.")
            sb.appendLine("# Edit <platform>.tuning.json to override — applied on mode switch.")
            sb.appendLine("# ====================================================================")
            sb.appendLine()

            for (path in allPaths) {
                val policy = Regex("""policy\d+""").find(path)?.value
                val allowed = when {
                    govParams.matches(path) && policy != null -> availGovs[policy] ?: ""
                    freqParams.matches(path) && policy != null ->
                        "${availFreqs[policy]?.split(" ")?.firstOrNull() ?: "?"}..${availFreqs[policy]?.split(" ")?.lastOrNull() ?: "?"} KHz"
                    path.contains("cores_online") -> "0, 1"
                    path.endsWith("thermal_sconfig") -> "0..7 (MIUI thermal profiles)"
                    path.endsWith("renderer") -> "default, opengl, skiagl, skiavk"
                    path.endsWith("vulkan") -> "false, true (needs resetprop + reboot)"
                    else -> ""
                }
                sb.append("[${path}]")
                if (allowed.isNotEmpty()) sb.append("  allowed: $allowed")
                sb.appendLine()
                sb.appendLine("  stock       : ${stockFlat[path] ?: "-"}")
                for (mode in ProfileKey.ALL_WITH_RELEASE) {
                    sb.appendLine("  ${mode.padEnd(12)}: ${perProfile[mode]?.get(path) ?: "-"}")
                }
                sb.appendLine()
            }

            dir().mkdirs()
            File(dir(), "Parameter.sh").writeText(sb.toString())
        } catch (ex: Exception) {
            ShellLog.log("DeviceProfileStore.catalog", ex.message ?: "error", error = true)
        }
    }
}
