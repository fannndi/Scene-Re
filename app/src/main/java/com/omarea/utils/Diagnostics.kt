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

                zip.putNextEntry(ZipEntry("miu-integration.txt"))
                zip.write(buildMiuInfo(context).toByteArray())
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

    /**
     * Read-only MIUI probe: the Joyose GameInfo provider (MIUI's own game list
     * and per-game mode/FPS columns), the PowerKeeper feature table and the
     * Game Turbo settings keys. Used to keep the integration in sync with what
     * the running MIUI build actually exposes; every query is harmless on AOSP.
     */
    private fun buildMiuInfo(context: Context): String {
        val sb = StringBuilder()
        sb.append("MIUI integration probe\n")
        sb.append("(read-only; empty sections mean the ROM does not expose them)\n\n")

        sb.append("Joyose GameInfo provider\n")
        sb.append(truncate(shell(
            "content query --uri content://com.xiaomi.Joyose.providergame_info 2> /dev/null"
        )))
        sb.append("\n\n")

        sb.append("PowerKeeper feature table\n")
        sb.append(truncate(shell(
            "content query --uri content://com.miui.powerkeeper.configure/GlobalFeatureTable 2> /dev/null"
        )))
        sb.append("\n\n")

        sb.append("Game Turbo settings\n")
        var any = false
        for (key in listOf("is_gamebooster", "screen_game_mode", "game_booster", "game_mode")) {
            for (namespace in listOf("secure", "system", "global")) {
                val value = shell("settings get $namespace $key 2> /dev/null").trim()
                if (value.isNotEmpty() && value != "null") {
                    sb.append("  ").append(namespace).append('.').append(key)
                        .append(" = ").append(value).append('\n')
                    any = true
                }
            }
        }
        if (!any) {
            sb.append("  (none found)\n")
        }

        sb.append("\nQTI perf hints: ").append(QtiPerfHints.probe()).append('\n')

        // MIUI's own booster service. The status line is the quickest way to
        // tell "the ROM has no such service" from "the UID allow-list entry is
        // missing", which need different fixes.
        sb.append("MIUI booster: ").append(MiuiBoosterHints.probe(context)).append('\n')
        sb.append("  authorized: ").append(MiuiBoosterHints.isAuthorized()).append('\n')
        sb.append("  mibridge_auth_uids: ")
            .append(shell("getprop persist.sys.mibridge_auth_uids 2> /dev/null").trim().ifEmpty { "(unset)" })
            .append('\n')
        sb.append("  enable_miui_booster: ")
            .append(shell("getprop persist.sys.enable_miui_booster 2> /dev/null").trim().ifEmpty { "(unset)" })
            .append('\n')

        // The MIUI thermal control interface. These nodes decide how hard
        // mi_thermald throttles; reading them makes a thermal complaint
        // actionable instead of guesswork.
        sb.append("\nMIUI thermal interface\n")
        for (node in listOf(
            "sconfig", "temp_state", "board_sensor", "board_sensor_temp", "cpu_limits", "boost", "screen_state"
        )) {
            val value = shell("cat /sys/class/thermal/thermal_message/$node 2> /dev/null").trim()
            sb.append("  thermal_message/").append(node).append(" = ")
                .append(value.ifEmpty { "(unreadable)" }).append('\n')
        }
        sb.append("  mi_thermald: ")
            .append(shell("getprop init.svc.mi_thermald 2> /dev/null").trim().ifEmpty { "(not running)" })
            .append(" pid=").append(shell("pidof mi_thermald 2> /dev/null").trim().ifEmpty { "-" })
            .append('\n')

        // mi_thermald's own policy state. The global mode is the key into
        // /vendor/etc/thermal-map.conf; the runtime config directory holds
        // MIUI/Game Turbo overrides and wins over /vendor/etc; thermal.dump is
        // the daemon's last computed sensor/target table.
        val globalMode = shell("cat /data/vendor/thermal/thermal-global-mode 2> /dev/null").trim()
        val modeName = mapOf(
            "0" to "thermal-normal.conf",
            "8" to "thermal-phone.conf",
            "9" to "thermal-tgame.conf (game)",
            "10" to "thermal-nolimits.conf",
            "12" to "thermal-camera.conf",
            "13" to "thermal-tgame.conf (game)",
            "15" to "thermal-arvr.conf",
            "16" to "thermal-tgame.conf (game)"
        )[globalMode] ?: "?"
        sb.append("\nMIUI thermal policy\n")
        sb.append("  global mode: ").append(globalMode.ifEmpty { "(absent)" })
            .append(" -> ").append(modeName).append('\n')
        val overrides = shell("ls /data/vendor/thermal/config 2> /dev/null").trim()
        sb.append("  runtime config overrides: ")
            .append(overrides.replace('\n', ' ').ifEmpty { "(none)" }).append('\n')
        sb.append("  persist.sys.thermal.config = ")
            .append(shell("getprop persist.sys.thermal.config 2> /dev/null").trim().ifEmpty { "(unset)" })
            .append('\n')
        sb.append("  thermal-engine service: ")
            .append(shell("getprop init.svc.thermal-engine 2> /dev/null").trim().ifEmpty { "(not running)" })
            .append('\n')
        sb.append("  kernel msm_thermal: ")
            .append(
                if (shell("test -e /sys/module/msm_thermal/parameters/enabled && echo 1 2> /dev/null").trim() == "1") {
                    "present"
                } else {
                    "absent (surya kernel has no KTM module)"
                }
            )
            .append('\n')
        val dump = shell("tail -n 20 /data/vendor/thermal/thermal.dump 2> /dev/null").trim()
        sb.append("  thermal.dump (tail):\n")
        for (line in truncate(dump.ifEmpty { "(none)" }, 2000).lines()) {
            sb.append("    ").append(line).append('\n')
        }

        // MIUI's game cpuset buckets. If a game is pinned into /dev/cpuset/game
        // while the bucket's mask is wrong, the game ends up on fewer cores
        // than it should have - worth seeing in a report.
        sb.append("\nMIUI cpusets\n")
        for (bucket in listOf("game", "gamelite", "top-app", "foreground", "background")) {
            val mask = shell("cat /dev/cpuset/$bucket/cpus 2> /dev/null").trim()
            if (mask.isNotEmpty()) {
                sb.append("  ").append(bucket).append(" = ").append(mask).append('\n')
            }
        }
        val gameProcs = shell("cat /dev/cpuset/game/cgroup.procs 2> /dev/null").trim()
        sb.append("  game procs: ").append(gameProcs.ifEmpty { "(empty)" }).append('\n')

        // Platform switches Scene patches on top of.
        sb.append("\nMIUI platform switches\n")
        sb.append("  sys.sptm.gover = ")
            .append(shell("getprop sys.sptm.gover 2> /dev/null").trim().ifEmpty { "(unset)" }).append('\n')
        sb.append("  sched_little_cluster_coloc_fmin_khz = ")
            .append(shell("cat /proc/sys/kernel/sched_little_cluster_coloc_fmin_khz 2> /dev/null").trim().ifEmpty { "(absent)" })
            .append('\n')
        sb.append("  persist.sys.miui_mi_fluency.thermal_break = ")
            .append(shell("getprop persist.sys.miui_mi_fluency.thermal_break 2> /dev/null").trim().ifEmpty { "(unset)" })
            .append('\n')
        sb.append("  persist.sys.miui_mi_fluency.enabled = ")
            .append(shell("getprop persist.sys.miui_mi_fluency.enabled 2> /dev/null").trim().ifEmpty { "(unset)" })
            .append('\n')

        // zram: MIUI sizes it from perfinit.conf, so the effective size is the
        // first thing to check when a memory complaint comes in.
        val zramSize = shell("cat /sys/block/zram0/disksize 2> /dev/null").trim()
        val zramAlgo = shell("cat /sys/block/zram0/comp_algorithm 2> /dev/null").trim()
        sb.append("\nzram\n")
        sb.append("  disksize = ")
            .append(if (zramSize.isNotEmpty() && zramSize.toLongOrNull() != null) {
                "${zramSize.toLong() / 1048576} MB"
            } else {
                "(not set up)"
            })
            .append('\n')
        sb.append("  comp_algorithm = ").append(zramAlgo.ifEmpty { "(absent)" }).append('\n')

        return sb.toString()
    }

    private fun shell(command: String): String = try {
        KeepShellPublic.doCmdSync(command)
    } catch (ex: Exception) {
        ""
    }

    private fun truncate(text: String, max: Int = 4000): String =
        if (text.length <= max) text else text.substring(0, max) + "\n... (truncated)"

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
