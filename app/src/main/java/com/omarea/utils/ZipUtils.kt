package com.omarea.utils

import android.content.Context
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Shared zip helpers. Both Diagnostics (support bundle) and ConfigBackup
 * (restorable archive) need the app preferences in their archives; this keeps
 * the entry layout identical for restore.
 */
object ZipUtils {
    /** Add every XML file below `shared_prefs` under the `shared_prefs/` prefix. */
    fun addSharedPrefs(context: Context, zip: ZipOutputStream) {
        val prefsDir = File(context.dataDir, "shared_prefs")
        prefsDir.listFiles()?.forEach { file ->
            if (file.isFile && file.extension == "xml") {
                zip.putNextEntry(ZipEntry("shared_prefs/${file.name}"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}
