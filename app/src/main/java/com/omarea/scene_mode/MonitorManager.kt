package com.omarea.scene_mode

import android.content.Context
import com.omarea.common.shared.FileWrite
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.ShellEscape
import com.omarea.store.SpfConfig
import com.omarea.utils.SceneLog
import java.io.File

/**
 * Lifecycle of the optional app_process foreground monitor.
 *
 * The monitor is a fallback for devices where the accessibility service is
 * killed or disabled: it switches to the game profile on its own. The actually
 * installed powercfg script is located through a small generated wrapper, so
 * the monitor does not need to know which provider the app chose.
 */
object MonitorManager {
    private const val PROCESS_NAME = "sys.scene-monitor"
    private const val MONITOR_DIR = "/data/adb/scene/monitor"
    private const val STATUS_PATH = "$MONITOR_DIR/app_status"
    private const val SWITCH_PATH = "$MONITOR_DIR/switch.sh"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(SpfConfig.GLOBAL_SPF_MONITOR_FALLBACK, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(SpfConfig.GLOBAL_SPF_MONITOR_FALLBACK, enabled).apply()
        if (enabled) {
            start(context)
        } else {
            stop()
        }
    }

    fun start(context: Context) {
        try {
            stop()
            writeSwitchWrapper(context)

            val apk = context.packageCodePath
            val prefsDir = context.dataDir.absolutePath + "/shared_prefs"
            val globalPrefs = "$prefsDir/${SpfConfig.GLOBAL_SPF}.xml"
            val appPrefs = "$prefsDir/${SpfConfig.APP_PROFILE_OPTIONS_SPF}.xml"
            val options = FileWrite.writePrivateShellFile(
                "addin/scene_profile_options.sh",
                "addin/scene_profile_options.sh",
                context
            ) ?: ""
            val boost = FileWrite.writePrivateShellFile(
                "addin/scene_qualcomm_boost.sh",
                "addin/scene_qualcomm_boost.sh",
                context
            ) ?: ""
            // Both option scripts source this lib from their own directory.
            FileWrite.writePrivateShellFile(
                "addin/scene_tune_lib.sh",
                "addin/scene_tune_lib.sh",
                context
            )
            val gameMode = prefs(context).getString(SpfConfig.GLOBAL_SPF_MONITOR_GAME_MODE, ModeSwitcher.PERFORMANCE)
                ?: ModeSwitcher.PERFORMANCE

            KeepShellPublic.doCmdSync(
                "mkdir -p " + ShellEscape.quote(MONITOR_DIR) + "\n" +
                    "(nohup app_process -Djava.class.path=" + ShellEscape.quote(apk) +
                    " / --nice-name=" + PROCESS_NAME + " com.omarea.scene_mode.SystemMonitor " +
                    ShellEscape.quote(STATUS_PATH) + " " +
                    ShellEscape.quote(GameListStore.effectiveFilePath()) + " " +
                    ShellEscape.quote(globalPrefs) + " " +
                    ShellEscape.quote(appPrefs) + " " +
                    ShellEscape.quote(SWITCH_PATH) + " " +
                    ShellEscape.quote(options) + " " +
                    ShellEscape.quote(boost) + " " +
                    ShellEscape.quote(gameMode) +
                    " > /dev/null 2>&1 &) \n" +
                    "sleep 1\n" +
                    "if pgrep -f $PROCESS_NAME > /dev/null; then echo started; else echo failed; fi"
            ).let { result ->
                SceneLog.i("Monitor", "start result: " + result.trim())
            }
        } catch (ex: Exception) {
            SceneLog.e("Monitor", "failed to start", ex)
        }
    }

    fun stop() {
        KeepShellPublic.doCmdSync("pkill -f $PROCESS_NAME")
    }

    fun isRunning(): Boolean = KeepShellPublic.doCmdSync("pgrep -f $PROCESS_NAME").trim().isNotEmpty()

    private fun prefs(context: Context) =
        context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)

    /**
     * The wrapper locates the powercfg provider the same way initPowerCfg does:
     * an external /data/powercfg.sh wins, otherwise the app's own copy.
     */
    private fun writeSwitchWrapper(context: Context) {
        val privateScript = FileWrite.getPrivateFilePath(context, "powercfg.sh")
        val privateDir = File(privateScript).parent ?: context.filesDir.absolutePath
        val script = """
            #!/system/bin/sh
            mode="${'$'}1"
            top="${'$'}2"
            [ -z "${'$'}top" ] && top=""
            export top_app="${'$'}top"
            if [ -f /data/powercfg.sh ]; then
                sh /data/powercfg.sh "${'$'}mode" > /dev/null 2>&1
                exit ${'$'}?
            fi
            for candidate in "$privateScript" $(find "$privateDir/powercfg" -name 'active.sh' -o -name 'conservative.sh' 2> /dev/null); do
                if [ -f "${'$'}candidate" ]; then
                    sh "${'$'}candidate" "${'$'}mode" > /dev/null 2>&1
                    exit ${'$'}?
                fi
            done
            exit 1
        """.trimIndent() + "\n"

        try {
            KeepShellPublic.doCmdSync(
                "mkdir -p " + ShellEscape.quote(MONITOR_DIR) + "\n" +
                    "cat > " + ShellEscape.quote(SWITCH_PATH) + " << 'SCENE_SWITCH_EOF'\n" +
                    script + "SCENE_SWITCH_EOF\n" +
                    "chmod 0755 " + ShellEscape.quote(SWITCH_PATH)
            )
        } catch (ex: Exception) {
            SceneLog.e("Monitor", "failed to write switch wrapper", ex)
        }
    }
}
