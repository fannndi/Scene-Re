package com.omarea.core.diagnostics

import android.content.Context
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.ShellLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * Collects a structured snapshot of device + app state, rendered either as
 * LLM-friendly Markdown or JSON. Read-only: nothing here mutates device state.
 */
object DiagnosticsCollector {

    /** Literal dollar sign for shell expressions inside Kotlin raw strings. */
    private const val D = "$"


    data class Section(val title: String, val body: String, val isCode: Boolean = true)

    private fun appVersion(context: Context): Pair<String, Long> = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        (pi.versionName ?: "?") to pi.longVersionCode
    } catch (ex: Exception) { "?" to 0L }

    private fun sh(cmd: String): String = KeepShellPublic.doCmdSync(cmd)

    fun collectSections(context: Context): List<Section> {
        val sections = ArrayList<Section>()

        sections += Section(
            "Device & build",
            sh(
                """
                echo "model        : $(getprop ro.product.model)"
                echo "device       : $(getprop ro.product.device)"
                echo "board        : $(getprop ro.board.platform)"
                echo "brand        : $(getprop ro.product.brand)"
                echo "android      : $(getprop ro.build.version.release) (SDK $(getprop ro.build.version.sdk))"
                echo "miui         : $(getprop ro.miui.ui.version.name)"
                echo "build        : $(getprop ro.build.display.id)"
                echo "kernel       : $(uname -r)"
                echo "boot         : $(getprop ro.bootmode) / $(getprop sys.boot_completed)"
                """.trimIndent()
            )
        )

        sections += Section(
            "Root & tools",
            sh(
                """
                echo "id           : $(id -u)"
                echo "which su     : $(which su)"
                echo "busybox      : $(busybox 2>/dev/null | head -1)"
                echo "magisk       : $(magisk -v 2>/dev/null)"
                """.trimIndent()
            )
        )

        sections += Section(
            "App",
            buildString {
                val (vName, vCode) = appVersion(context)
                appendLine("versionName : $vName")
                appendLine("versionCode : $vCode")
                appendLine("uptime      : ${android.os.SystemClock.elapsedRealtime() / 1000}s")
                appendLine("powercfg    : ${sh("getprop vtools.powercfg").trim()}")
                appendLine("powercfg_app: ${sh("getprop vtools.powercfg_app").trim()}")
                appendLine("battery_pause: ${sh("getprop vtools.bp").trim()}")
            },
            isCode = false
        )

        sections += Section(
            "CPU (cpufreq / core_ctl)",
            sh(
                """
                echo "online       : $(cat /sys/devices/system/cpu/online)"
                for p in /sys/devices/system/cpu/cpufreq/policy*; do
                  echo "--- $(basename ${D}p)"
                  echo "  governor : $(cat ${D}p/scaling_governor 2>/dev/null)"
                  echo "  cur      : $(cat ${D}p/scaling_cur_freq 2>/dev/null)"
                  echo "  min/max  : $(cat ${D}p/scaling_min_freq 2>/dev/null) / $(cat ${D}p/scaling_max_freq 2>/dev/null)"
                  echo "  avail    : $(cat ${D}p/scaling_available_frequencies 2>/dev/null)"
                  echo "  hispeed  : $(cat ${D}p/schedutil/hispeed_freq 2>/dev/null)"
                done
                echo "--- core_ctl"
                echo "  cpu0 enable=$(cat /sys/devices/system/cpu/cpu0/core_ctl/enable 2>/dev/null) min=$(cat /sys/devices/system/cpu/cpu0/core_ctl/min_cpus 2>/dev/null)"
                echo "  cpu6 enable=$(cat /sys/devices/system/cpu/cpu6/core_ctl/enable 2>/dev/null) min=$(cat /sys/devices/system/cpu/cpu6/core_ctl/min_cpus 2>/dev/null)"
                echo "--- msm_performance"
                echo "  max: $(cat /sys/module/msm_performance/parameters/cpu_max_freq 2>/dev/null)"
                echo "--- boost"
                echo "  input_boost_freq: $(cat /sys/module/cpu_boost/parameters/input_boost_freq 2>/dev/null)"
                echo "  input_boost_ms  : $(cat /sys/module/cpu_boost/parameters/input_boost_ms 2>/dev/null)"
                """.trimIndent()
            )
        )

        sections += Section(
            "GPU (kgsl)",
            sh(
                """
                echo "governor : $(cat /sys/class/kgsl/kgsl-3d0/devfreq/governor 2>/dev/null)"
                echo "cur      : $(cat /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq 2>/dev/null)"
                echo "min/max  : $(cat /sys/class/kgsl/kgsl-3d0/devfreq/min_freq 2>/dev/null) / $(cat /sys/class/kgsl/kgsl-3d0/devfreq/max_freq 2>/dev/null)"
                echo "avail    : $(cat /sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies 2>/dev/null)"
                echo "pwrlevel : min=$(cat /sys/class/kgsl/kgsl-3d0/min_pwrlevel 2>/dev/null) max=$(cat /sys/class/kgsl/kgsl-3d0/max_pwrlevel 2>/dev/null) num=$(cat /sys/class/kgsl/kgsl-3d0/num_pwrlevels 2>/dev/null)"
                echo "gpu busy : $(cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage 2>/dev/null)"
                """.trimIndent()
            )
        )

        sections += Section(
            "Thermal",
            sh(
                """
                for z in /sys/class/thermal/thermal_zone*; do
                  echo "$(cat ${D}z/type 2>/dev/null): $(cat ${D}z/temp 2>/dev/null)"
                done
                echo "--- miui thermal_message"
                echo "sconfig           : $(cat /sys/class/thermal/thermal_message/sconfig 2>/dev/null)"
                echo "board_sensor_temp : $(cat /sys/class/thermal/thermal_message/board_sensor_temp 2>/dev/null)"
                """.trimIndent()
            )
        )

        sections += Section(
            "Memory & swap",
            sh(
                """
                grep -E "MemTotal|MemAvailable|SwapTotal|SwapFree|Cached" /proc/meminfo
                echo "zram mm_stat: $(cat /sys/block/zram0/mm_stat 2>/dev/null)"
                echo "swappiness  : $(cat /proc/sys/vm/swappiness 2>/dev/null)"
                """.trimIndent()
            )
        )

        sections += Section(
            "Battery",
            sh(
                """
                b=/sys/class/power_supply/battery
                echo "capacity : $(cat ${D}b/capacity 2>/dev/null)"
                echo "status   : $(cat ${D}b/status 2>/dev/null)"
                echo "current  : $(cat ${D}b/current_now 2>/dev/null)"
                echo "voltage  : $(cat ${D}b/voltage_now 2>/dev/null)"
                echo "temp     : $(cat ${D}b/temp 2>/dev/null)"
                echo "charge_en: $(cat ${D}b/battery_charging_enabled 2>/dev/null)"
                echo "cc_max   : $(cat ${D}b/constant_charge_current_max 2>/dev/null)"
                """.trimIndent()
            )
        )

        sections += Section(
            "Cpuset & stune",
            sh(
                """
                echo "background        : $(cat /dev/cpuset/background/cpus 2>/dev/null)"
                echo "system-background : $(cat /dev/cpuset/system-background/cpus 2>/dev/null)"
                echo "foreground        : $(cat /dev/cpuset/foreground/cpus 2>/dev/null)"
                echo "top-app           : $(cat /dev/cpuset/top-app/cpus 2>/dev/null)"
                echo "sched_boost       : $(cat /proc/sys/kernel/sched_boost 2>/dev/null)"
                echo "top-app prefer_idle: $(cat /dev/stune/top-app/schedtune.prefer_idle 2>/dev/null) boost: $(cat /dev/stune/top-app/schedtune.boost 2>/dev/null)"
                """.trimIndent()
            )
        )

        sections += Section("Recent shell executions (ShellLog)", ShellLog.dump(), isCode = true)

        val logcat = sh("logcat -d -t 400 *:E")
        val logcatTail = logcat.lines().takeLast(200).joinToString("\n")
        sections += Section("Logcat (errors, last 200 lines)", logcatTail, isCode = true)

        return sections
    }

    fun buildMarkdown(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("# Scene diagnostics report")
        sb.appendLine()
        sb.appendLine("- Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
        val (vName, vCode) = appVersion(context)
        sb.appendLine("- Package: ${context.packageName} v$vName ($vCode)")
        sb.appendLine()
        for (s in collectSections(context)) {
            sb.appendLine("## ${s.title}")
            sb.appendLine()
            if (s.body.isBlank()) {
                sb.appendLine("_empty_")
            } else if (s.isCode) {
                sb.appendLine("```")
                sb.appendLine(s.body.trim())
                sb.appendLine("```")
            } else {
                sb.appendLine(s.body.trim())
            }
            sb.appendLine()
        }
        return sb.toString()
    }

    fun buildJson(context: Context): String {
        val root = JSONObject()
        val (vName, vCode) = appVersion(context)
        root.put("app", JSONObject().put("package", context.packageName).put("versionName", vName).put("versionCode", vCode))
        root.put("generatedAt", System.currentTimeMillis())
        val arr = JSONArray()
        for (s in collectSections(context)) {
            val o = JSONObject()
            o.put("section", s.title)
            o.put("body", s.body.trim())
            arr.put(o)
        }
        root.put("sections", arr)
        return root.toString(2)
    }
}
