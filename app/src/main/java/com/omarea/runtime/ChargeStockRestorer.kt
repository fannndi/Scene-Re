package com.omarea.runtime

/**
 * One-time cleanup of legacy Scene charge-control artifacts.
 *
 * Scene used to control charging (battery protection pause, QC current
 * limits); charging is **read-only** now — the ROM/kernel owns every charge
 * parameter. Older versions persisted three artifacts that outlive a write:
 *  - `vtools.bp` = 1 (charging was paused),
 *  - `vtools.charge.current.max` / `/data/adb/.scene_ccmax` (backup of the
 *    original `constant_charge_current_max` before we lowered it),
 *  - `vtools.fastcharge` (marker that the fast-charge prep ran).
 *
 * The emitted command undoes exactly those and nothing else: it resumes a
 * paused charge, writes the backed-up original current-max back, then clears
 * props and the backup file. Every step is guarded, so on a clean device it
 * performs zero node writes (sysfs charge nodes already reset to kernel
 * defaults on reboot — only our persisted artifacts need undoing).
 *
 * Responsibility: emitting the restore command.
 * Non-goals: ongoing charge control (gone by policy) and charge-state reads
 * (DiagnosticsCollector).
 */
object ChargeStockRestorer {

    /** Shell command; safe (no-op) when no artifact exists. */
    fun command(): String = """
        # legacy charge-control artifacts -> restore ROM/kernel defaults
        if [ "${'$'}(getprop vtools.bp 2>/dev/null)" = "1" ]; then
          B=/sys/class/power_supply/battery
          [ -f ${'$'}B/battery_charging_enabled ] && chmod 0666 ${'$'}B/battery_charging_enabled && echo 1 > ${'$'}B/battery_charging_enabled
          [ -f ${'$'}B/input_suspend ] && chmod 0666 ${'$'}B/input_suspend && echo 0 > ${'$'}B/input_suspend
          [ -f /sys/class/qcom-battery/input_suspend ] && chmod 0666 /sys/class/qcom-battery/input_suspend && echo 0 > /sys/class/qcom-battery/input_suspend
          setprop vtools.bp 0
        fi
        restore="${'$'}(getprop vtools.charge.current.max 2>/dev/null)"
        if [ -z "${'$'}restore" ] && [ -f /data/adb/.scene_ccmax ]; then
          restore="${'$'}(cat /data/adb/.scene_ccmax 2>/dev/null)"
        fi
        if [ -n "${'$'}restore" ]; then
          for p in /sys/class/power_supply/*/constant_charge_current_max; do
            [ -f "${'$'}p" ] || continue
            chmod 0664 "${'$'}p" 2>/dev/null
            echo "${'$'}restore" > "${'$'}p" 2>/dev/null
          done
        fi
        setprop vtools.charge.current.max ""
        setprop vtools.fastcharge ""
        rm -f /data/adb/.scene_ccmax
    """.trimIndent()

    /** True when any persisted artifact is present (for diagnostics/tests). */
    fun hasArtifacts(bpProp: String, ccmaxProp: String, fastchargeProp: String, backupFileExists: Boolean): Boolean =
        bpProp == "1" || ccmaxProp.isNotEmpty() || fastchargeProp.isNotEmpty() || backupFileExists
}
