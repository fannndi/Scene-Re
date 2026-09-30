package com.omarea.engine

/**
 * Fast-charge limit commands — the Kotlin port of
 * `assets/addin/{fast_charge,fast_charge_run_once}.sh`.
 *
 * Pure command builders (unit-tested); execution goes through the root shell
 * exactly like the old scripts did (`BatteryUtils.setChargeInputLimit`).
 *
 * Behaviour preserved 1:1:
 *  - `runOnce()` prepares the Qualcomm charge nodes and backs up the
 *    current max into the `vtools.charge.current.max` prop (once per boot);
 *  - `limit()` writes the mA limit, optionally only raising (`onlyTaller`);
 *  - Xiaomi 11 Pro/Ultra (mars/star) use `constant_charge_current` instead
 *    of the `*_max` glob.
 *
 * Responsibility: building the commands.
 * Non-goals: running them, deciding limits (BatteryUtils owns that).
 */
object FastCharge {

    /** Node the run-once step backs up from. */
    private const val CURRENT_MAX =
        "/sys/class/power_supply/battery/constant_charge_current_max"
    private const val CURRENT_MAX_PROP = "vtools.charge.current.max"
    private const val FASTCHARGE_PROP = "vtools.fastcharge"

    /** Xiaomi 11 Pro/Ultra charge-node layout. */
    fun isMi11Series(device: String): Boolean = device == "mars" || device == "star"

    /**
     * One-shot preparation (was `fast_charge_run_once.sh`): relax the charge
     * safeguards and remember the stock current max so it can be restored.
     */
    fun runOnce(): String = """
        set_value() { if [ -f "\$1" ]; then chmod 0664 "\$1"; echo "\$2" > "\$1"; fi; }
        set_value /sys/class/qcom-battery/restricted_charging 0
        set_value /sys/class/power_supply/usb/boost_current 1
        set_value /sys/class/power_supply/battery/restricted_charging 0
        set_value /sys/class/power_supply/allow_hvdcp3 1
        set_value /sys/class/power_supply/battery/safety_timer_enabled 0
        set_value /sys/class/power_supply/bms/temp_warm 500
        chmod 0664 /sys/class/power_supply/main/constant_charge_current_max 2>/dev/null
        chmod 0664 $CURRENT_MAX 2>/dev/null
        if [ -z "$(getprop $CURRENT_MAX_PROP)" ] && [ -f $CURRENT_MAX ]; then
          setprop $CURRENT_MAX_PROP $(cat $CURRENT_MAX)
        fi
        setprop $FASTCHARGE_PROP 1
    """.trimIndent()

    /**
     * The limit write (was `fast_charge.sh <limit> <onlyTaller>`).
     * Call [runOnce] first (BatteryUtils guards it with `vtools.fastcharge`).
     * @param limit mA value (the old script defaulted to 3000).
     * @param onlyTaller true = never lower the existing limit (step-up mode).
     * @param device Build.DEVICE — selects the mi11 node layout.
     */
    fun limit(limit: Int, onlyTaller: Boolean, device: String): String {
        val ma = "${limit}000" // mA → µA
        if (isMi11Series(device)) {
            return "chmod 0664 /sys/class/power_supply/battery/constant_charge_current 2>/dev/null; " +
                "echo $ma > /sys/class/power_supply/battery/constant_charge_current 2>/dev/null"
        }
        return buildString {
            appendLine("for p in /sys/class/power_supply/*/constant_charge_current_max; do")
            appendLine("  [ -f \"\$p\" ] || continue")
            appendLine("  chmod 0664 \"\$p\" 2>/dev/null")
            if (onlyTaller) {
                appendLine("  cur=\$(cat \"\$p\" 2>/dev/null)")
                appendLine("  if [ -n \"\$cur\" ] && [ \"\$cur\" -lt $ma ] 2>/dev/null; then echo $ma > \"\$p\" 2>/dev/null; fi")
            } else {
                appendLine("  echo $ma > \"\$p\" 2>/dev/null")
            }
            appendLine("done")
        }.trim()
    }
}
