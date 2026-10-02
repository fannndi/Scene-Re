package com.omarea.runtime

import android.content.Context
import com.omarea.engine.RootShell
import com.omarea.util.CheckRootStatus

/**
 * "Dead-man switch" for the app-uninstall case.
 *
 * Everything Scene writes into the kernel is per-boot and heals on reboot, but
 * four things survive an uninstall: PM state (suspended/disabled/hidden apps),
 * Tweaks settings, the APatch modules and the `/data/local/tmp` artifacts.
 *
 * This module is refreshed on every rooted boot. Its `service.sh` does exactly
 * one thing when the app data dir is gone: replay [PmStateJournal] (restore
 * apps/settings), delete every Scene module + tmp artifact and then itself.
 * While the app is installed the script exits immediately.
 *
 * Responsibility: provision the tiny guard module + mirror the journal.
 * Non-goals: the cleanup itself (runs in shell at boot), in-app cleanup
 * ([SceneCleanup]).
 */
object SceneGuard {

    const val DIR = "/data/adb/modules/scene_guard"
    private const val ID = "scene_guard"

    /** (Re)writes module.prop + service.sh. Root only; silent no-op otherwise. */
    fun sync(context: Context) {
        if (!CheckRootStatus.isAvailable()) return
        val prop = """
            id=$ID
            name=Scene cleanup guard
            version=1.0.0
            versionCode=1
            author=Scene
            description=Restores Scene's PM/settings changes and removes Scene modules when the app is uninstalled. Managed by the Scene app.
        """.trimIndent()

        val out = RootShell.run(
            "mkdir -p $DIR\n" +
                "cat > $DIR/module.prop <<'SCENE_GUARD'\n$prop\nSCENE_GUARD\n" +
                "cat > $DIR/service.sh <<'SCENE_GUARD'\n$SCRIPT\nSCENE_GUARD\n" +
                "chmod 0755 $DIR/service.sh\n" +
                "chmod 0644 $DIR/module.prop\n" +
                "echo ok"
        )
        if (out.contains("ok")) {
            syncJournal(context)
        }
    }

    /** Mirrors [PmStateJournal] + platform game downscales into the module dir. Root only. */
    fun syncJournal(context: Context) {
        if (!CheckRootStatus.isAvailable()) return
        val lines = PmStateJournal.lines(context)
        val games = DownscaleController.packages(context)
        RootShell.run(
            "mkdir -p $DIR\n" +
                "cat > $DIR/journal.pm <<'SCENE_JOURNAL'\n$lines\nSCENE_JOURNAL\n" +
                "cat > $DIR/journal.game <<'SCENE_GAMES'\n" + games.joinToString("\n") + "\nSCENE_GAMES\n" +
                "chmod 0644 $DIR/journal.pm $DIR/journal.game"
        )
    }

    fun remove() {
        RootShell.run("rm -rf $DIR")
    }

    /**
     * The boot script. Runs as `service.sh` (late_start service): `pm`,
     * `settings` and `setprop` are all available at that point.
     * Exposed for the JVM contract test.
     */
    internal val SCRIPT = """
        #!/system/bin/sh
        # Scene cleanup guard — auto-generated, do not edit.
        # App still installed? Do nothing.
        MODDIR=${'$'}{0%/*}
        [ -d /data/data/com.omarea.vtools ] && exit 0

        # App uninstalled: undo Scene's persistent effects.
        J="${'$'}MODDIR/journal.pm"
        if [ -f "${'$'}J" ]; then
          while IFS='|' read -r kind value; do
            [ -n "${'$'}value" ] || continue
            case "${'$'}kind" in
              suspend) pm unsuspend "${'$'}value" >/dev/null 2>&1 ;;
              disable) pm enable "${'$'}value" >/dev/null 2>&1 ;;
              hide)    pm unhide "${'$'}value" >/dev/null 2>&1 ;;
              setting) settings delete "${'$'}{value%%:*}" "${'$'}{value#*:}" >/dev/null 2>&1 ;;
            esac
          done < "${'$'}J"
        fi

        # Remove every Scene module and tmp artifact, then this module itself.
        G="${'$'}MODDIR/journal.game"
        if [ -f "${'$'}G" ]; then
          while read -r pkg; do
            [ -n "${'$'}pkg" ] && cmd game downscale disable "${'$'}pkg" >/dev/null 2>&1
          done < "${'$'}G"
        fi

        # Never leave charging paused (bypass-charging rule-12 exception).
        for p in /sys/class/power_supply/battery/battery_charging_enabled \
                 /sys/class/power_supply/battery/input_suspend \
                 /sys/class/qcom-battery/input_suspend; do
          [ -e "${'$'}p" ] || continue
          case "${'$'}p" in
            */battery_charging_enabled) echo 1 > "${'$'}p" 2>/dev/null ;;
            *) echo 0 > "${'$'}p" 2>/dev/null ;;
          esac
        done

        # Opt-in extras: restart the logger services and restore the stock
        # kernel panic values (surya: 5/0/1, probe-verified).
        start statsd 2>/dev/null; start traced 2>/dev/null; start charge_logger 2>/dev/null
        echo 5 > /proc/sys/kernel/panic 2>/dev/null
        echo 0 > /proc/sys/kernel/panic_on_warn 2>/dev/null
        echo 1 > /proc/sys/kernel/panic_on_oops 2>/dev/null

        rm -rf /data/adb/modules/scene_sepolicy /data/adb/modules/scene_systemless /data/adb/modules/scene_resurgence
        rm -f /data/local/tmp/scene_thermald.sh /data/local/tmp/scene_thermald.profile_max /data/local/tmp/scene_thermald.state /data/local/tmp/scene_thermald.stop /data/local/tmp/scene_policy.rules
        setprop persist.vtools.suspend ""
        rm -rf "${'$'}MODDIR"
    """.trimIndent()
}
