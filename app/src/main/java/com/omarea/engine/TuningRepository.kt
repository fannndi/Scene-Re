package com.omarea.engine

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
 *   User copy       : /sdcard/Scene/profiles/<platform>.tuning.json
 *                     (editable via VS Code over adb or any file manager)
 *
 * The user copy wins when present.
 *
 * Responsibility: locating, reading and writing tuning JSON files.
 * Non-goals: interpreting the JSON (planner) or executing it (applier).
 */
object TuningRepository {

    private const val ASSET_ROOT = "powercfg"

    fun dir(): File = File(Environment.getExternalStorageDirectory(), "Scene/profiles")

    fun userFile(platform: String): File = File(dir(), "$platform.tuning.json")

    fun hasUserCopy(platform: String): Boolean = userFile(platform).isFile

    /** Effective tuning: user copy → bundled asset copy. */
    fun read(context: Context, platform: String): JSONObject? {
        try {
            val user = userFile(platform)
            if (user.isFile) return JSONObject(user.readText())
            context.assets.open("$ASSET_ROOT/$platform/tuning.json").bufferedReader().use {
                return JSONObject(it.readText())
            }
        } catch (ex: Exception) {
            ShellLog.log("TuningRepository.read", ex.message ?: "error", error = true)
        }
        return null
    }

    fun readUserText(platform: String): String? =
        userFile(platform).takeIf { it.isFile }?.readText()

    fun writeUser(platform: String, content: String): Boolean = try {
        dir().mkdirs()
        userFile(platform).writeText(content)
        true
    } catch (ex: Exception) {
        false
    }

    /** Copies the bundled tuning to the user folder (only if absent). */
    fun ensureUserCopy(context: Context, platform: String) {
        if (hasUserCopy(platform)) return
        try {
            dir().mkdirs()
            userFile(platform).writeText(bundledText(context, platform))
        } catch (_: Exception) {
        }
    }

    fun bundledText(context: Context, platform: String): String =
        context.assets.open("$ASSET_ROOT/$platform/tuning.json")
            .bufferedReader().use { it.readText() }

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
                    context.getString(R.string.tuning_profiles),
                    context.getString(R.string.tuning_profiles_path, dir().absolutePath)
                )
            }
        }
    }
}
