package com.omarea.runtime

import android.content.Context
import com.omarea.engine.RootShell
import com.omarea.util.CheckRootStatus

/**
 * Journal of every persistent state Scene changes outside its own data dir.
 *
 * The PM state (`pm suspend`/`disable`/`hide`) and the Tweaks screen's
 * `settings` keys survive both reboots and an app uninstall. The journal is
 * mirrored into the [SceneGuard] module (`journal.pm`), whose boot script
 * restores everything when the app is gone — so uninstalling Scene can never
 * leave frozen apps or tweaked settings behind.
 *
 * Entry format (one per line, also the module file format):
 *   `suspend|<pkg>` · `disable|<pkg>` · `hide|<pkg>` · `setting|<ns>:<key>`
 *
 * Responsibility: record/clear/restore + powers the guard module's journal.
 * Non-goals: executing the actions (callers own their commands).
 */
object PmStateJournal {

    private const val PREFS = "scene_journal"
    private const val KEY = "entries"
    private val lock = Any()

    // ------------------------------------------------------------- recording
    fun record(context: Context, kind: String, value: String) {
        if (value.isBlank()) return
        synchronized(lock) {
            val set = entries(context).toMutableSet()
            set.add("$kind|$value")
            save(context, set)
        }
        SceneGuard.sync(context)
    }

    fun clear(context: Context, kind: String, value: String) {
        synchronized(lock) {
            val set = entries(context).toMutableSet()
            if (set.remove("$kind|$value")) {
                save(context, set)
                SceneGuard.sync(context)
            }
        }
    }

    /** Removes every entry for [pkg] (used when an app is fully unfrozen). */
    fun clearPackage(context: Context, pkg: String) {
        synchronized(lock) {
            val set = entries(context).toMutableSet()
            val before = set.size
            set.removeAll(listOf("suspend|$pkg", "disable|$pkg", "hide|$pkg"))
            if (set.size != before) {
                save(context, set)
                SceneGuard.sync(context)
            }
        }
    }

    /** Removes a `setting|ns:key` entry. */
    fun clearSetting(context: Context, namespace: String, key: String) {
        clear(context, "setting", "$namespace:$key")
    }

    /** Removes every entry of one kind (e.g. all `suspend` after standby off). */
    fun clearKind(context: Context, kind: String) {
        synchronized(lock) {
            val set = entries(context).toMutableSet()
            val before = set.size
            set.removeAll(set.filter { it.startsWith("$kind|") }.toSet())
            if (set.size != before) {
                save(context, set)
                SceneGuard.sync(context)
            }
        }
    }

    fun clearAll(context: Context) {
        synchronized(lock) { save(context, emptySet()) }
        SceneGuard.sync(context)
    }

    fun entries(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet()) ?: emptySet()

    /** Journal lines for the guard module file. */
    fun lines(context: Context): String = entries(context).sorted().joinToString("\n")

    // --------------------------------------------------------------- restore
    /**
     * In-app restore (root): undoes every journalled state. Returns the number
     * of entries processed. The guard module runs the exact same actions in
     * shell when the app is gone.
     */
    fun restoreAll(context: Context): Int {
        if (!CheckRootStatus.isAvailable()) return 0
        val list = entries(context).sorted()
        if (list.isEmpty()) return 0
        RootShell.run(restoreScript(list))
        clearAll(context)
        return list.size
    }

    /** Pure: shell script undoing [entries] (same semantics as the guard). */
    internal fun restoreScript(entries: Collection<String>): String = buildString {
        for (entry in entries) {
            val idx = entry.indexOf('|')
            if (idx <= 0) continue
            val kind = entry.substring(0, idx)
            val value = entry.substring(idx + 1)
            when (kind) {
                "suspend" -> appendLine("pm unsuspend \"$value\" 2>/dev/null")
                "disable" -> appendLine("pm enable \"$value\" 2>/dev/null")
                "hide" -> appendLine("pm unhide \"$value\" 2>/dev/null")
                "setting" -> {
                    val sep = value.indexOf(':')
                    if (sep > 0) {
                        appendLine(
                            "settings delete \"${value.substring(0, sep)}\" " +
                                "\"${value.substring(sep + 1)}\" 2>/dev/null"
                        )
                    }
                }
            }
        }
    }

    private fun save(context: Context, set: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY, set).apply()
    }
}
