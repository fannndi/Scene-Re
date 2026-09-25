package com.omarea.scene_mode.game

import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.ShellEscape
import com.omarea.utils.SceneLog

/**
 * Read-only view of MIUI's own per-game Game Turbo record.
 *
 * The source is MIUI's `com.xiaomi.joyose.smartop.provider.GameInfoProvider`
 * (DB `GameInfo.db`), whose rows carry `pkg`, `name`, `Mode`, `enable`, `fps`
 * and `gamemode`. The exact meaning of Mode/gamemode is MIUI-version specific,
 * so the values are only shown to the user — never written — and the query is
 * a no-op on AOSP ROMs.
 */
object MiuGameInfo {
    const val URI = "content://com.xiaomi.Joyose.providergame_info"

    private const val CACHE_MS = 30_000L

    data class Row(
        val pkg: String,
        val name: String = "",
        val mode: String = "",
        val gameMode: String = "",
        val enable: String = "",
        val fps: String = ""
    )

    @Volatile
    private var cached: Map<String, Row>? = null

    @Volatile
    private var cachedAt = 0L

    /** Parse `content query` output; only rows carrying a package are kept. */
    fun parse(output: String): Map<String, Row> {
        val rows = HashMap<String, Row>()
        for (line in output.lineSequence()) {
            if (!line.trim().startsWith("Row:")) {
                continue
            }
            val pkg = Regex("\\bpkg=([^\\s,]+)").find(line)?.groupValues?.get(1) ?: continue
            val row = Row(
                pkg = pkg,
                name = field(line, "name"),
                mode = field(line, "Mode"),
                gameMode = field(line, "gamemode"),
                enable = field(line, "enable"),
                fps = field(line, "fps")
            )
            rows[pkg] = row
        }
        return rows
    }

    private fun field(line: String, key: String): String =
        Regex("(?:^|,\\s*)" + Regex.escape(key) + "=([^,]*)", RegexOption.IGNORE_CASE)
            .find(line)?.groupValues?.get(1)?.trim() ?: ""

    /** Cached query of MIUI's game records (empty on non-MIUI ROMs). */
    fun query(): Map<String, Row> {
        val hit = cached
        if (hit != null && System.currentTimeMillis() - cachedAt < CACHE_MS) {
            return hit
        }
        val rows = try {
            parse(
                KeepShellPublic.doCmdSync(
                    "content query --uri " + ShellEscape.quote(URI) + " 2> /dev/null"
                )
            )
        } catch (ex: Exception) {
            SceneLog.e("MiuGameInfo", "query failed", ex)
            emptyMap()
        }
        cached = rows
        cachedAt = System.currentTimeMillis()
        return rows
    }

    fun invalidate() {
        cached = null
    }

    /** Compact human summary of a MIUI record, e.g. "enabled · mode 1 · 60 FPS". */
    fun summary(row: Row): String {
        val parts = ArrayList<String>()
        when (row.enable) {
            "1" -> parts.add("enabled")
            "0" -> parts.add("disabled")
        }
        if (row.mode.isNotEmpty()) {
            parts.add("mode " + row.mode)
        }
        if (row.gameMode.isNotEmpty() && row.gameMode != row.mode) {
            parts.add("game mode " + row.gameMode)
        }
        if (row.fps.isNotEmpty()) {
            parts.add(row.fps + " FPS")
        }
        return if (parts.isEmpty()) row.name.ifEmpty { "listed" } else parts.joinToString(" \u00B7 ")
    }
}
