package com.omarea.utils

import android.content.Context
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.ShellEscape
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup and restore of Scene's own configuration: the shared preferences
 * (profiles, charge settings, profile options) and the swap config that lives
 * under /data/adb/scene.
 *
 * The archive is created in the app's external files dir so no storage
 * permission is needed, then copied to /sdcard/Download/Scene for easy access.
 */
object ConfigBackup {
    private const val EXPORT_DIR = "/sdcard/Download/Scene"
    private const val SWAP_CONF = "/data/adb/scene/swap.conf"

    fun export(context: Context): String? {
        return try {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val fileName = "scene-config-$stamp.zip"
            val externalDir = context.getExternalFilesDir(null) ?: context.filesDir
            val output = File(externalDir, fileName)

            ZipOutputStream(output.outputStream().buffered()).use { zip ->
                val prefsDir = File(context.dataDir, "shared_prefs")
                prefsDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.extension == "xml") {
                        zip.putNextEntry(ZipEntry("shared_prefs/${file.name}"))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }

                val swapConf = KeepShellPublic.doCmdSync("cat " + ShellEscape.quote(SWAP_CONF) + " 2> /dev/null")
                if (swapConf.isNotBlank() && !swapConf.contains("No such file")) {
                    zip.putNextEntry(ZipEntry("scene/swap.conf"))
                    zip.write(swapConf.toByteArray())
                    zip.closeEntry()
                }
            }

            KeepShellPublic.doCmdSync(
                "mkdir -p " + ShellEscape.quote(EXPORT_DIR) + "\n" +
                    "cp " + ShellEscape.quote(output.absolutePath) + " " + ShellEscape.quote("$EXPORT_DIR/$fileName")
            )
            "$EXPORT_DIR/$fileName"
        } catch (ex: Exception) {
            SceneLog.e("ConfigBackup", "export failed", ex)
            null
        }
    }

    /** Newest archive in the export directory, or null. */
    fun latest(): String? {
        val listing = KeepShellPublic.doCmdSync(
            "ls -t " + ShellEscape.quote(EXPORT_DIR) + " 2> /dev/null | head -n 1"
        ).trim()
        return if (listing.isEmpty()) null else "$EXPORT_DIR/$listing"
    }

    /** Restore a backup archive. The caller must restart the app afterwards. */
    fun restore(context: Context, zipPath: String): Boolean {
        return try {
            val archive = File(context.cacheDir, "scene-restore.zip")
            val copied = KeepShellPublic.doCmdSync(
                "cp " + ShellEscape.quote(zipPath) + " " + ShellEscape.quote(archive.absolutePath) + " && echo ok"
            ).trim()
            if (copied != "ok") {
                return false
            }

            var swapRestored = false
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    when {
                        name.startsWith("shared_prefs/") && name.endsWith(".xml") -> {
                            val target = File(context.dataDir, name)
                            target.parentFile?.mkdirs()
                            target.outputStream().use { zip.copyTo(it) }
                        }
                        name == "scene/swap.conf" -> {
                            val content = zip.readBytes().toString(Charsets.UTF_8)
                            KeepShellPublic.doCmdSync(
                                "mkdir -p /data/adb/scene\n" +
                                    "cat > " + ShellEscape.quote(SWAP_CONF) + " << 'SCENE_EOF'\n" +
                                    content + "\nSCENE_EOF"
                            )
                            swapRestored = true
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            archive.delete()
            SceneLog.i("ConfigBackup", "restored $zipPath, swap=$swapRestored")
            true
        } catch (ex: Exception) {
            SceneLog.e("ConfigBackup", "restore failed", ex)
            false
        }
    }
}
