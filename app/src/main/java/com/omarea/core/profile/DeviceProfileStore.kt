package com.omarea.core.profile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import com.omarea.common.shell.ShellLog
import com.omarea.vtools.R
import org.json.JSONObject
import java.io.File

/**
 * Per-device tuning store.
 *
 *   Bundled default : assets/powercfg/<platform>/tuning.json  (device-exact)
 *   User copy       : /sdcard/Scene/profiles/<platform>.tuning.json (editable
 *                     via VS Code over adb, any file manager, or the in-app
 *                     editor). The user copy wins when present.
 *
 * Legacy kernel-profile sh templates were merged into the JSON — see
 * DeviceProfileEngine for the applier.
 */
object DeviceProfileStore {

    const val MODE_POWERSAVE = "powersave"
    const val MODE_BALANCE = "balance"
    const val MODE_PERFORMANCE = "performance"
    const val MODE_CUSTOM = "custom"

    val MODES = listOf(MODE_POWERSAVE, MODE_BALANCE, MODE_PERFORMANCE, MODE_CUSTOM)

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
}
