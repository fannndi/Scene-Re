package com.omarea.runtime

import android.content.Context

/**
 * Per-app overrides for the game extras (AZenith per-game settings parity).
 *
 * `powercfg_game` prefs: `prio:<pkg>` / `pre:<pkg>` booleans. `null` means
 * "no per-app choice" and the global toggle applies.
 *
 * Responsibility: read/write the per-app choices.
 * Non-goals: acting on them (ProcessPriority/GamePreload read them).
 */
object GameExtras {

    private const val PREFS = "powercfg_game"

    fun priority(context: Context, packageName: String): Boolean? =
        boolOrNull(context, "prio:$packageName")

    fun preload(context: Context, packageName: String): Boolean? =
        boolOrNull(context, "pre:$packageName")

    fun setPriority(context: Context, packageName: String, enabled: Boolean) =
        set(context, "prio:$packageName", enabled)

    fun setPreload(context: Context, packageName: String, enabled: Boolean) =
        set(context, "pre:$packageName", enabled)

    private fun boolOrNull(context: Context, key: String): Boolean? {
        val p = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (p.contains(key)) p.getBoolean(key, false) else null
    }

    private fun set(context: Context, key: String, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(key, enabled).apply()
    }
}
