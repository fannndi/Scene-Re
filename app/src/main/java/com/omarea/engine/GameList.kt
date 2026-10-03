package com.omarea.engine

import android.content.Context

/**
 * Bundled "known games" package list.
 *
 * Source: Encore Tweaks `gamelist.txt` (Apache-2.0, Rem01Gaming) — see
 * `docs/ATTRIBUTION.md`. Used to
 *  - seed per-app mode entries on a fresh install (AppSwitchHandler),
 *  - badge games in the per-app mode list (AdapterSceneMode),
 *  - report the count in Diagnostics.
 *
 * The list is data, not policy: user-configured per-app modes always win.

 *
 * Responsibility: Bundled known-games package list (seed for per-app PERFORMANCE defaults).
 * Non-goals: applying modes — AppSwitchHandler owns that.
 */
object GameList {

    private const val ASSET = "games/encore_gamelist.txt"

    /** A plausible Android package name (used for asset validation/tests). */
    val PACKAGE_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")

    @Volatile
    private var cached: Set<String>? = null

    /** Parses + validates the bundled list once per process. */
    fun load(context: Context): Set<String> {
        cached?.let { return it }
        val set = try {
            context.assets.open(ASSET).bufferedReader().useLines { lines ->
                lines.map { it.trim() }
                    .filter { it.isNotEmpty() && PACKAGE_REGEX.matches(it) }
                    .toCollection(LinkedHashSet())
            }
        } catch (_: Exception) {
            emptySet()
        }
        cached = set
        return set
    }

    fun isGame(context: Context, packageName: String): Boolean =
        packageName in load(context)

    fun size(context: Context): Int = load(context).size
}
