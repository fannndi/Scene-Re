package com.omarea.scene_mode

import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.ShellEscape
import com.omarea.utils.SceneLog

/**
 * The profile / active-game status files under /data/adb/scene (the same
 * concept as Encore Tweaks' addon interface, Scene paths).
 *
 * Shared by the app and by the app_process fallback monitor so both write
 * exactly the same format; only the shell backend differs. Scripts and other
 * root tools can observe the state through:
 *
 *   /data/adb/scene/current_profile   the active mode name
 *   /data/adb/scene/gameinfo          "<package> <pid> <uid>" or "NULL 0 0"
 */
object SceneStatus {
    const val PROFILE_FILE = "/data/adb/scene/current_profile"
    const val GAME_FILE = "/data/adb/scene/gameinfo"

    /**
     * `<package> <pid> <uid>` for the status file, or `NULL 0 0` when the
     * package is empty or no longer running. The pid/uid lookup runs through
     * [exec] so the monitor can use its own lightweight shell.
     */
    fun gameInfo(packageName: String, exec: (String) -> String): String {
        if (packageName.isEmpty()) {
            return "NULL 0 0"
        }
        val out = exec(
            "pidof " + ShellEscape.quote(packageName) + " 2> /dev/null | tr ' ' '\\n' | head -n 1\n" +
                "stat -c %u " + ShellEscape.quote("/data/data/$packageName") + " 2> /dev/null"
        )
        val parts = out.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val pid = parts.getOrElse(0) { "0" }
        val uid = parts.getOrElse(1) { "0" }
        // A dead package (pidof empty) is reported as no active session.
        return if (pid.isEmpty() || pid == "0") {
            "NULL 0 0"
        } else {
            "$packageName $pid $uid"
        }
    }

    /** Shell command that publishes both status files. */
    fun command(mode: String, gameInfo: String): String {
        val cmd = StringBuilder("mkdir -p /data/adb/scene\n")
        if (mode.isNotEmpty()) {
            cmd.append("echo ").append(ShellEscape.quote(mode)).append(" > ").append(PROFILE_FILE).append("\n")
        }
        cmd.append("cat > ").append(GAME_FILE).append(" << 'SCENE_STATUS_EOF'\n")
            .append(gameInfo).append("\nSCENE_STATUS_EOF")
        return cmd.toString()
    }

    /** Write both files; [exec] defaults to the app's persistent root shell. */
    fun write(
        mode: String,
        packageName: String,
        game: Boolean,
        exec: (String) -> String = { KeepShellPublic.doCmdSync(it) }
    ) {
        try {
            val info = if (game) gameInfo(packageName, exec) else "NULL 0 0"
            exec(command(mode, info))
        } catch (ex: Exception) {
            SceneLog.e("SceneStatus", "write failed", ex)
        }
    }
}
