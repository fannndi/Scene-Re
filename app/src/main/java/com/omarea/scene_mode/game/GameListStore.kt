package com.omarea.scene_mode.game

import android.content.Context
import android.content.pm.ApplicationInfo
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.ShellEscape
import com.omarea.utils.SceneLog

/**
 * Game list with a bundled baseline, persisted as a plain text file under
 * /data/adb/scene.
 *
 * The bundled list (adapted from Encore Tweaks, Apache-2.0) gives instant
 * detection coverage even when the ROM does not tag games with the game
 * category. The user file adds packages and can exclude a bundled entry with a
 * `!package` line. The effective list is materialised into a second file so the
 * app_process monitor can read it without the app's assets.
 *
 * The accessibility service, the profile options layer and the optional
 * app_process monitor all read the same effective file.
 */
object GameListStore {
    private const val FILE = "/data/adb/scene/games.txt"
    private const val EFFECTIVE_FILE = "/data/adb/scene/games_effective.txt"
    private const val DEFAULT_ASSET = "addin/game_list_default.txt"

    @Volatile
    private var cache: Set<String>? = null

    @Volatile
    private var defaults: Set<String>? = null

    /** Absolute path of the merged list, for the companion monitor. */
    fun effectiveFilePath(): String = EFFECTIVE_FILE

    private fun defaults(context: Context): Set<String> {
        defaults?.let { return it }
        val set = try {
            context.assets.open(DEFAULT_ASSET).bufferedReader().useLines { lines ->
                lines.map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toSet()
            }
        } catch (ex: Exception) {
            SceneLog.e("GameList", "failed to read the bundled game list", ex)
            emptySet()
        }
        defaults = set
        return set
    }

    private fun readUserFile(): Set<String> {
        val content = KeepShellPublic.doCmdSync("cat " + ShellEscape.quote(FILE) + " 2> /dev/null")
        return content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()
    }

    fun games(context: Context): Set<String> {
        cache?.let { return it }
        val result = defaults(context).toMutableSet()
        for (line in readUserFile()) {
            if (line.startsWith("!")) {
                result.remove(line.substring(1))
            } else {
                result.add(line)
            }
        }
        cache = result
        writeEffective(result)
        return result
    }

    fun isGame(context: Context, packageName: String?): Boolean =
        !packageName.isNullOrEmpty() && games(context).contains(packageName)

    fun setGame(context: Context, packageName: String?, game: Boolean) {
        if (packageName.isNullOrEmpty()) {
            return
        }
        val user = readUserFile().toMutableSet()
        if (game) {
            user.remove("!" + packageName)
            if (!defaults(context).contains(packageName)) {
                user.add(packageName)
            }
        } else {
            user.remove(packageName)
            if (defaults(context).contains(packageName)) {
                user.add("!" + packageName)
            }
        }
        writeUser(user)
        cache = null
        games(context)
    }

    fun invalidate() {
        cache = null
    }

    /** Merge every installed app that declares the game category into the list. */
    fun syncCategoryGames(context: Context) {
        try {
            val bundled = defaults(context)
            val user = readUserFile().toMutableSet()
            val excluded = user.filter { it.startsWith("!") }.map { it.substring(1) }.toSet()
            var changed = false
            context.packageManager.getInstalledApplications(0).forEach { info ->
                if (info.category == ApplicationInfo.CATEGORY_GAME &&
                    !excluded.contains(info.packageName) &&
                    !user.contains(info.packageName) &&
                    !bundled.contains(info.packageName)
                ) {
                    user.add(info.packageName)
                    changed = true
                }
            }
            if (changed) {
                writeUser(user)
            }
            cache = null
            games(context)
        } catch (ex: Exception) {
            SceneLog.e("GameList", "category sync failed", ex)
        }
    }

    private fun writeUser(lines: Set<String>) {
        writeFile(FILE, lines.sorted().joinToString("\n"))
    }

    private fun writeEffective(games: Set<String>) {
        writeFile(EFFECTIVE_FILE, games.sorted().joinToString("\n"))
    }

    private fun writeFile(path: String, body: String) {
        KeepShellPublic.doCmdSync(
            "mkdir -p /data/adb/scene\n" +
                "cat > " + ShellEscape.quote(path) + " << 'SCENE_GAME_LIST_EOF'\n" +
                body + "\nSCENE_GAME_LIST_EOF"
        )
    }
}
