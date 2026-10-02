package com.omarea.runtime

import android.content.Context
import com.omarea.common.shell.ShellLog
import com.omarea.data.SpfConfig
import com.omarea.engine.ProfileController
import com.omarea.engine.RootShell
import com.omarea.util.AccessibleServiceHelper
import com.omarea.util.CheckRootStatus

/**
 * Opt-in a11y-free foreground watcher (AZenith's AppMonitor, shell edition).
 *
 * AZenith runs a Kotlin `app_process` monitor with hidden-API bypass. Scene
 * deliberately does not ship that (no hidden-API dependency, no extra root
 * process): a small shell loop polls `dumpsys activity activities` every 3 s
 * and broadcasts the focused package to [ReceiverForeground]. It only runs
 * when the user enables it **and** the accessibility service is not running,
 * so the two paths can never fight. CPU cost ≈ 0.1 s per 3 s poll.
 *
 * Responsibility: deploy/start/stop the watcher script.
 * Non-goals: handling the foreground change (ForegroundFallback).
 */
object RootForegroundWatch {

    const val ACTION = "com.omarea.vtools.action.FOREGROUND"
    private const val SCRIPT = "/data/local/tmp/scene_fgwatch.sh"
    private const val STATE = "/data/local/tmp/scene_fg.state"
    private const val STOP = "/data/local/tmp/scene_fgwatch.stop"

    fun isEnabled(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
        .getBoolean(SpfConfig.GLOBAL_SPF_ROOT_WATCH, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
            .edit().putBoolean(SpfConfig.GLOBAL_SPF_ROOT_WATCH, enabled).apply()
        if (enabled) start(context) else stop(context)
    }

    /** Starts the watcher when allowed; a11y running -> not needed. */
    fun start(context: Context) {
        val app = context.applicationContext
        if (!allowed(app)) return
        if (runCatching { AccessibleServiceHelper().serviceRunning(app) }.getOrDefault(false)) {
            ShellLog.log("RootForegroundWatch", "a11y active — fallback not started")
            return
        }
        runCatching {
            RootShell.run(
                "cat > $SCRIPT <<'SCENE_FG'\n$SCRIPT_TEXT\nSCENE_FG\n" +
                    "chmod 0755 $SCRIPT\n" +
                    "rm -f $STATE $STOP\n" +
                    "nohup sh $SCRIPT >/dev/null 2>&1 < /dev/null &\n" +
                    "echo started"
            )
            ShellLog.log("RootForegroundWatch", "watcher started")
        }
    }

    /** Stops the watcher (own change; allowed on every exit path). */
    fun stop(context: Context) {
        runCatching {
            RootShell.run(
                "touch $STOP 2>/dev/null\n" +
                    "sleep 0.5\n" +
                    "pkill -f scene_fgwatch.sh 2>/dev/null\n" +
                    "rm -f $SCRIPT $STOP $STATE"
            )
            ShellLog.log("RootForegroundWatch", "watcher stopped")
        }
    }

    /** Diagnostics (blocking shell call; run off the main thread). */
    fun isRunning(): Boolean = runCatching {
        RootShell.run("pgrep -f scene_fgwatch.sh 2>/dev/null").trim().isNotEmpty()
    }.getOrDefault(false)

    private fun allowed(context: Context): Boolean =
        !TrueOff.isOff(context) &&
            !ProfileController.isEngineOff(context) &&
            CheckRootStatus.isAvailable()

    /**
     * The watcher script. `mResumedActivity` is the probe-verified source on
     * this MIUI build (`dumpsys window windows` has no mCurrentFocus here).
     */
    internal val SCRIPT_TEXT = """
        #!/system/bin/sh
        # Scene foreground fallback watcher (a11y-free) — auto-generated.
        STATE=$STATE
        STOP=$STOP
        LAST=""
        while true; do
          if [ -f "${'$'}STOP" ]; then rm -f "${'$'}STOP"; exit 0; fi
          PKG=${'$'}(dumpsys activity activities 2>/dev/null | grep -m1 mResumedActivity | sed -n 's/.*u0 \([a-zA-Z0-9._]*\)\/.*/\1/p')
          if [ -n "${'$'}PKG" ] && [ "${'$'}PKG" != "${'$'}LAST" ]; then
            LAST="${'$'}PKG"
            echo "${'$'}PKG" > "${'$'}STATE"
            am broadcast -a $ACTION --es packageName "${'$'}PKG" \
              -n com.omarea.vtools/com.omarea.runtime.ReceiverForeground >/dev/null 2>&1
          fi
          sleep 3
        done
    """.trimIndent()
}
