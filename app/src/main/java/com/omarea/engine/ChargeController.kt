package com.omarea.engine


/**
 * Native replacement for assets/addin/{disable,resume}_charge.sh.
 * Pauses charging through Qualcomm battery power-supply nodes.
 */
object ChargeController {

    private const val BCE = "/sys/class/power_supply/battery/battery_charging_enabled"
    private const val SUSPEND = "/sys/class/power_supply/battery/input_suspend"
    private const val SUSPEND2 = "/sys/class/qcom-battery/input_suspend"
    private const val MAX = "/sys/class/power_supply/battery/constant_charge_current_max"

    /** True if the device exposes any of the nodes required to pause charging. */
    fun supported(): Boolean = RootShell.run(
        "if [ -f $BCE ] || [ -f $SUSPEND ] || [ -f $SUSPEND2 ] || [ -f $MAX ]; then echo yes; fi"
    ).contains("yes")

    /** Stops charging (battery pause). Returns false when unsupported. */
    fun pause(): Boolean {
        val out = RootShell.run(
            """
            if [ -f $BCE ] || [ -f $SUSPEND ] || [ -f $SUSPEND2 ]; then
              [ -f $BCE ] && chmod 0666 $BCE && echo 0 > $BCE
              [ -f $SUSPEND ] && chmod 0666 $SUSPEND && echo 1 > $SUSPEND
              [ -f $SUSPEND2 ] && chmod 0666 $SUSPEND2 && echo 1 > $SUSPEND2
              setprop vtools.bp 1
              echo ok
            elif [ -f $MAX ]; then
              [ ! -f /data/adb/.scene_ccmax ] && chmod 0666 $MAX && cat $MAX > /data/adb/.scene_ccmax 2>/dev/null
              chmod 0666 $MAX && echo 0 > $MAX
              setprop vtools.bp 1
              echo ok
            else
              echo error
            fi
            """.trimIndent()
        )
        return out.contains("ok")
    }

    /** Resumes charging. Returns false when unsupported. */
    fun resume(): Boolean {
        val out = RootShell.run(
            """
            if [ -f $MAX ] && [ "$(cat $MAX)" = "0" ]; then
              chmod 0666 $MAX
              if [ -s /data/adb/.scene_ccmax ]; then
                echo $(cat /data/adb/.scene_ccmax) > $MAX
              else
                echo 3000000 > $MAX
              fi
            fi
            if [ -f $BCE ] || [ -f $SUSPEND ] || [ -f $SUSPEND2 ]; then
              [ -f $BCE ] && chmod 0666 $BCE && echo 1 > $BCE
              [ -f $SUSPEND ] && chmod 0666 $SUSPEND && echo 0 > $SUSPEND
              [ -f $SUSPEND2 ] && chmod 0666 $SUSPEND2 && echo 0 > $SUSPEND2
              setprop vtools.bp 0
              echo ok
            else
              echo error
            fi
            """.trimIndent()
        )
        return out.contains("ok")
    }
}
