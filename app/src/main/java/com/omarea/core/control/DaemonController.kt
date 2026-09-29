package com.omarea.core.control

import android.content.Context
import com.omarea.common.shell.ShellLog
import com.omarea.core.shell.RootShell
import com.omarea.core.shell.ShellNodes

/**
 * Owns the thermal-daemon lifecycle that follows the profile engine state.
 *
 * Profile engine ON  : stop MIUI mi_thermald/miuibooster, run scene_thermald
 *                      (only ever lowers scaling_max when hot).
 * Profile engine OFF : restore the MIUI daemons and stop scene_thermald.
 *
 * Responsibility: daemon stop/start + deployment of the bundled script.
 * Non-goals: kernel tuning, profile JSON.
 */
object DaemonController {

    private const val MI_THERMALD = "mi_thermald"
    private const val MIUIBOOSTER = "miuibooster"

    /** Bracket pattern avoids pgrep/pkill matching its own command line. */
    private const val THERMALD_PATTERN = "scene_thermald[.]sh"

    fun ensureOn(context: Context) {
        RootShell.run("stop $MI_THERMALD")
        RootShell.run("stop $MIUIBOOSTER")
        ensureSceneThermaldRunning(context)
    }

    fun ensureOff(context: Context) {
        RootShell.run("start $MI_THERMALD")
        RootShell.run("start $MIUIBOOSTER")
        RootShell.run(
            "pkill -f '$THERMALD_PATTERN' 2>/dev/null; " +
                "rm -f ${ShellNodes.THERMALD_PROFILE_MAX} ${ShellNodes.THERMALD_STOP}; true"
        )
    }

    fun isSceneThermaldRunning(): Boolean =
        RootShell.run("pgrep -f '$THERMALD_PATTERN'").isNotBlank()

    private fun ensureSceneThermaldRunning(context: Context) {
        if (isSceneThermaldRunning()) return
        deploy(context)
        RootShell.run("nohup sh ${ShellNodes.THERMALD_SCRIPT} >/dev/null 2>&1 < /dev/null &")
    }

    /** Deploys the bundled scene_thermald.sh to /data/local/tmp (SELinux-safe). */
    fun deploy(context: Context) {
        try {
            val text = context.assets.open("scene_thermald.sh").bufferedReader().use { it.readText() }
            RootShell.run(
                "cat > ${ShellNodes.THERMALD_SCRIPT} <<'SCENE_EOF'\n$text\nSCENE_EOF\n" +
                    "chmod 755 ${ShellNodes.THERMALD_SCRIPT}"
            )
        } catch (ex: Exception) {
            ShellLog.log("DaemonController.deploy", ex.message ?: "error", error = true)
        }
    }
}
