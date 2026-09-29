package com.omarea.core.profile

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.ShellLog
import com.omarea.vtools.R
import java.io.File

/**
 * User-editable kernel profiles living in /sdcard/Scene/profiles/.
 *
 *   init.sh        — base tuning, applied once at profile-engine init
 *   powersave.sh   — applied while Powersave mode is active
 *   balance.sh     — applied while Balance mode is active
 *   performance.sh — applied while Performance mode is active
 *   custom.sh      — applied while Custom mode is active (user playground)
 *   profile.log    — execution output appended on every apply
 *
 * Files are plain shell, writable without root (edited via any file manager,
 * in-app editor or VS Code over adb). Execution runs through su.
 * Missing files fall back to the bundled scripts / native applier.
 */
object KernelProfileLoader {

    const val MODE_POWERSAVE = "powersave"
    const val MODE_BALANCE = "balance"
    const val MODE_PERFORMANCE = "performance"
    const val MODE_CUSTOM = "custom"

    val MODE_FILES = listOf("powersave", "balance", "performance", "custom")

    fun dir(): File = File(android.os.Environment.getExternalStorageDirectory(), "Scene/profiles")

    fun file(mode: String): File = File(dir(), "$mode.sh")

    fun hasProfile(mode: String): Boolean = file(mode).isFile && file(mode).length() > 0

    fun hasInit(): Boolean = File(dir(), "init.sh").isFile

    fun logFile(): File = File(dir(), "profile.log")

    fun readProfile(mode: String): String =
        if (hasProfile(mode)) file(mode).readText() else ""

    fun writeProfile(mode: String, content: String): Boolean = try {
        dir().mkdirs()
        file(mode).writeText(content)
        true
    } catch (ex: Exception) {
        false
    }

    /** Extracts the bundled templates when the folder has no mode files yet. */
    fun ensureTemplates(context: Context) {
        val d = dir()
        if (d.isDirectory && d.listFiles()?.any { it.isFile } == true) return
        d.mkdirs()
        val names = listOf("init", "powersave", "balance", "performance", "custom")
        for (name in names) {
            try {
                val text = context.assets.open("kernel-profile-template/$name.sh")
                    .bufferedReader().use { it.readText() }
                File(d, "$name.sh").writeText(text)
            } catch (_: Exception) {
            }
        }
    }

    /** Executes init.sh (user base tuning). Returns false when absent/failed. */
    fun applyInit(): Boolean {
        val f = File(dir(), "init.sh")
        if (!f.isFile) return false
        return exec("sh ${f.absolutePath}")
    }

    /** Executes the mode profile script. Returns false when absent/failed. */
    fun apply(mode: String): Boolean {
        val f = file(mode)
        if (!f.isFile || f.length() == 0L) return false
        return exec("sh ${f.absolutePath}")
    }

    private fun exec(cmd: String): Boolean {
        val out = KeepShellPublic.doCmdSync(
            "$cmd 2>&1 | tee -a ${logFile().absolutePath}"
        )
        val ok = !out.contains("not found") && !out.contains("Permission denied")
        ShellLog.log("KernelProfileLoader", "applied -> ${out.take(200)}", error = !ok)
        return ok
    }

    /** Opens the profiles folder in the system file manager (DocumentsUI). */
    fun openFolder(context: Context) {
        dir().mkdirs()
        val uri = Uri.parse(
            "content://com.android.externalstorage.documents/document/primary%3AScene%2Fprofiles"
        )
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setClassName("com.google.android.documentsui", "com.android.documentsui.files.FilesActivity")
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
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
