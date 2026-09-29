package com.omarea.engine

import com.omarea.common.shell.KeepShellPublic

/**
 * Command builders + parsers for the kernel/ROM tweaks that used to live in
 * kr-script shell pages.
 *
 * Pure string logic (unit-tested); execution happens through [run]/[read].
 *
 * Responsibility: build commands and parse their output.
 * Non-goals: UI, scheduling.
 */
object TweakCommands {

    // ------------------------------------------------------------- low power
    fun lowPowerRead(): String = "settings get global low_power"

    /** Mirrors the old aosp/ps/set.sh semantics. */
    fun lowPowerSet(on: Boolean): String {
        val value = if (on) "1" else "0"
        if (!on) {
            return "settings put global low_power $value; settings put global low_power_sticky $value"
        }
        return buildString {
            appendLine("settings put global low_power 1")
            appendLine("settings put global low_power_sticky 1")
            appendLine("settings put global app_auto_restriction_enabled true")
            appendLine("settings put global forced_app_standby_enabled 1")
            appendLine("settings put global app_standby_enabled 1")
            appendLine("settings put global forced_app_standby_for_small_battery_enabled 1")
        }.trim()
    }

    /** (value, label) pairs for the trigger-level pickers; null = system default. */
    val lowPowerLevels = listOf(
        "null" to "Default", "5" to "5%", "9" to "10%", "14" to "15%", "19" to "20%",
        "24" to "25%", "29" to "30%", "34" to "35%", "39" to "40%"
    )

    fun lowPowerLevelSet(level: String?, max: String?): String {
        val sb = StringBuilder()
        sb.appendLine(settingWrite("low_power_trigger_level", level))
        sb.appendLine(settingWrite("low_power_trigger_level_max", max))
        return sb.toString().trim()
    }

    private fun settingWrite(key: String, value: String?): String =
        if (value.isNullOrEmpty() || value == "null") {
            "settings delete global $key; settings reset global $key"
        } else {
            "settings put global $key $value"
        }

    // ------------------------------------------------------------- animation
    val animScaleOptions = listOf(
        "0.1" to "0.1x", "0.25" to "0.25x", "0.5" to "0.5x", "0.75" to "0.75x",
        "1" to "1.0x", "" to "System default", "1.25" to "1.25x", "1.5" to "1.5x",
        "1.75" to "1.75x", "2" to "2.0x", "2.5" to "2.5x", "3" to "3.0x"
    )

    /** MIUI-only extra: transition animation duration ratio. */
    val animRatioOptions = listOf(
        "0.01" to "0.01x", "0.1" to "0.1x", "0.25" to "0.25x", "0.5" to "0.5x",
        "0.75" to "0.75x", "1" to "1.0x"
    )

    val animKeys = mapOf(
        "window" to "window_animation_scale",
        "transition" to "transition_animation_scale",
        "animator" to "animator_duration_scale",
        "ratio" to "transition_animation_duration_ratio"
    )

    fun animSet(values: Map<String, String>): String =
        values.entries.joinToString("; ") { (short, value) ->
            val key = animKeys[short] ?: return@joinToString ""
            if (value.isEmpty()) "settings delete global $key" else "settings put global $key $value"
        }.replace("; ;", ";")

    fun animGetCommand(): String =
        animKeys.entries.joinToString("; ") { (short, key) -> "echo \"$short=$(settings get global $key)\"" }

    fun parseKeyValues(output: String): Map<String, String> =
        output.lines().mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
        }.toMap()

    // ---------------------------------------------------------------- gapps
    fun gappsGetCommand(): String =
        "disabled=\$(pm list packages -d | grep com.google.android.gsf); " +
            "if [ -n \"\$disabled\" ]; then echo 0; else echo 1; fi"

    val gappsPackages = listOf(
        "com.google.android.gsf", "com.google.android.gsf.login", "com.google.android.gms",
        "com.android.vending", "com.google.android.play.games",
        "com.google.android.syncadapters.contacts"
    )

    fun gappsSet(on: Boolean): String {
        val verb = if (on) "enable" else "disable"
        return gappsPackages.joinToString("\n") { "pm $verb $it 2>/dev/null" }
    }

    fun gappsSupportedCommand(): String =
        "installed=\$(pm list packages | grep com.google.android.gsf); " +
            "if [ -n \"\$installed\" ]; then echo 1; else echo 0; fi"

    // -------------------------------------------------------------- sensors
    val sensorsCommand =
        "for z in /sys/class/thermal/thermal_zone*; do " +
            "if [ -f \$z/temp ]; then echo \"\$(basename \$z)|\$(cat \$z/type)|\$(cat \$z/temp)\"; fi; done"

    /** Parses "zoneN|type|temp" lines. */
    fun parseSensors(output: String): List<Triple<String, String, String>> =
        output.lines().mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size != 3) null
            else Triple(parts[0].trim(), parts[1].trim(), parts[2].trim())
        }

    // --------------------------------------------------------------- storage
    val fstrimCommand =
        "echo 'fstrim /data'; fstrim /data; echo 'fstrim /cache'; fstrim /cache; " +
            "echo 'fstrim /system'; fstrim /system 2>/dev/null; sm fstrim 2>/dev/null"

    /** Reads the UFS health descriptor with the same fallbacks as the old script. */
    val ufsHealthCommand = """
        for d in \
          /sys/devices/platform/soc/1d84000.ufshc/health_descriptor \
          /sys/devices/virtual/mi_memory/mi_memory_device/ufshcd0 \
          /sys/kernel/debug/*.ufshc; do
          if [ -f "${'$'}d/dump_health_desc" ]; then cat "${'$'}d/dump_health_desc"; fi
          if [ -f "${'$'}d/dump_device_desc" ]; then echo '--- device ---'; cat "${'$'}d/dump_device_desc"; fi
        done
        for f in /sys/kernel/debug/*.ufshc/dump_health_desc; do [ -f "${'$'}f" ] && cat "${'$'}f"; done 2>/dev/null
        if [ -z "${'$'}(cat /sys/devices/platform/soc/1d84000.ufshc/health_descriptor/dump_health_desc 2>/dev/null)" ]; then
          echo "bDeviceLifeTimeEstA=${'$'}(cat /sys/devices/platform/soc/1d84000.ufshc/health_descriptor/life_time_estimation_a 2>/dev/null)"
        fi
    """.trimIndent()

    /** Parses "key = value" / "key=value" lines into a map. */
    fun parseUfs(output: String): Map<String, String> =
        output.lines().mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) null
            else line.substring(0, idx).trim().removePrefix("---") to line.substring(idx + 1).trim()
        }.filter { it.first.isNotEmpty() }.toMap()

    // ------------------------------------------------------------- perfmgr
    const val PERFMGR_NODE = "/sys/module/perfmgr/parameters/perfmgr_enable"

    fun perfmgrSet(on: Boolean): String =
        "chmod 644 $PERFMGR_NODE 2>/dev/null; echo ${if (on) 1 else 0} > $PERFMGR_NODE; " +
            if (on) "chmod 444 $PERFMGR_NODE" else "true"

    // ---------------------------------------------------------------- runner
    fun run(script: String): String = KeepShellPublic.doCmdSync(script)

    fun read(command: String): String = KeepShellPublic.doCmdSync(command).trim()
}
