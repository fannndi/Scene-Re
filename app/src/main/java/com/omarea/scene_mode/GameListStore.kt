package com.omarea.scene_mode

import android.content.Context
import android.content.pm.ApplicationInfo
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.ShellEscape
import com.omarea.utils.SceneLog

/**
 * User-managed game list, persisted as a plain text file under /data/adb/scene.
 *
 * The accessibility service, the profile options layer and the optional
 * app_process monitor all read the same file. The monitor runs outside the app
 * process and cannot open the app database, so a text file is the single source
 * of truth; apps that declare the game category are merged in automatically.
 */
object GameListStore {
    private const val FILE = "/data/adb/scene/games.txt"

    @Volatile
    private var cache: Set<String>? = null

    fun games(): Set<String> {
        cache?.let { return it }
        val content = KeepShellPublic.doCmdSync("cat " + ShellEscape.quote(FILE) + " 2> /dev/null")
        val set = content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()
        cache = set
        return set
    }

    fun isGame(packageName: String?): Boolean =
        !packageName.isNullOrEmpty() && games().contains(packageName)

    fun setGame(packageName: String?, game: Boolean) {
        if (packageName.isNullOrEmpty()) {
            return
        }
        val current = games().toMutableSet()
        if (game) {
            current.add(packageName)
        } else {
            current.remove(packageName)
        }
        write(current)
    }

    fun invalidate() {
        cache = null
    }

    /** Absolute path of the shared list, for the companion monitor. */
    fun filePath(): String = FILE

    /** Merge every installed app that declares the game category into the list. */
    fun syncCategoryGames(context: Context) {
        try {
            val games = games().toMutableSet()
            var changed = false
            context.packageManager.getInstalledApplications(0).forEach { info ->
                if (info.category == ApplicationInfo.CATEGORY_GAME && games.add(info.packageName)) {
                    changed = true
                }
            }
            if (changed) {
                write(games)
            }
        } catch (ex: Exception) {
            SceneLog.e("GameList", "category sync failed", ex)
        }
    }

    private fun write(games: Set<String>) {
        val body = games.sorted().joinToString("\n")
        KeepShellPublic.doCmdSync(
            "mkdir -p /data/adb/scene\n" +
                "cat > " + ShellEscape.quote(FILE) + " << 'SCENE_GAME_LIST_EOF'\n" +
                body + "\nSCENE_GAME_LIST_EOF"
        )
        cache = games
    }
}
