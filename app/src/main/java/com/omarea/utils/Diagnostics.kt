package com.omarea.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.omarea.common.shared.RootBackend
import com.omarea.common.shell.KeepShellPublic
import com.omarea.scene_mode.power.BatteryHealth
import com.omarea.utils.KernelCapabilities
import com.omarea.vtools.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Collects everything needed for a useful bug report into one archive: the
 * Scene log, device/SoC info, the resolved root backend and the app config.
 */
object Diagnostics {
    fun export(context: Context): File? {
        return try {
            val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val output = File(dir, "scene-diagnostics-$stamp.zip")

            ZipOutputStream(output.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("device-info.txt"))
                zip.write(buildDeviceInfo(context).toByteArray())
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("kernel-features.txt"))
                zip.write(
                    (KernelCapabilities.report(context, force = true) + "\n\n" + BatteryHealth.report())
                        .toByteArray()
                )
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("platform-support.txt"))
                zip.write(PlatformCapabilities.report(context).toByteArray())
                zip.closeEntry()

                SceneLog.logFilePath()?.let { path ->
                    val logFile = File(path)
                    if (logFile.isFile) {
                        zip.putNextEntry(ZipEntry("scene-log.txt"))
                        logFile.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }

                ZipUtils.addSharedPrefs(context, zip)
            }
            output
        } catch (ex: Exception) {
            SceneLog.e("Diagnostics", "export failed", ex)
            null
        }
    }

    fun share(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.diagnostics_share)))
        } catch (ex: Exception) {
            SceneLog.e("Diagnostics", "share failed", ex)
        }
    }

    private fun buildDeviceInfo(context: Context): String {
        val sb = StringBuilder()
        sb.append("Scene diagnostics\n")
        sb.append("generated: ").append(Date()).append("\n\n")
        sb.append("model: ").append(Build.MODEL).append(" (").append(Build.DEVICE).append(")\n")
        sb.append("brand: ").append(Build.BRAND).append(" / ").append(Build.MANUFACTURER).append("\n")
        sb.append("android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("board platform: ").append(KeepShellPublic.doCmdSync("getprop ro.board.platform").trim()).append("\n")
        sb.append("soc model: ").append(KeepShellPublic.doCmdSync("getprop ro.soc.model").trim()).append("\n")
        sb.append("build: ").append(KeepShellPublic.doCmdSync("getprop ro.build.display.id").trim()).append("\n")
        sb.append("root backend: ").append(RootBackend.diagnose()).append("\n")
        sb.append("kernel: ").append(KeepShellPublic.doCmdSync("uname -a").trim()).append("\n")
        return sb.toString()
    }
}
