package com.omarea.runtime

import android.content.Context
import com.omarea.common.shell.ShellLog
import com.omarea.data.SpfConfig
import com.omarea.engine.ProfileController
import com.omarea.engine.RootShell
import com.omarea.util.CheckRootStatus

/**
 * Game library preload (AZenith/vmtouch-derived, docs/ATTRIBUTION.md).
 *
 * When a game with its own mode comes to the foreground, its `lib/arm64`
 * `.so` files are read once through the root shell — the page cache is warm
 * before the game's own loader starts. No binary is shipped (no vmtouch);
 * a plain read is enough for the cache. Budget-bounded and rate-limited per
 * package (10 min) so rapid switching cannot spam the flash.
 *
 * Responsibility: one bounded read pass + logging.
 * Non-goals: deciding (pure [GamePreloadPolicy]), app detection.
 */
object GamePreload {

    private const val RATE_LIMIT_MS = 10 * 60 * 1000L

    private val lastPreloaded = HashMap<String, Long>()

    fun isEnabled(context: Context): Boolean = prefs(context)
        .getBoolean(SpfConfig.GLOBAL_SPF_GAME_PRELOAD, false)

    /** Effective choice: per-app override wins, else the global toggle. */
    fun effectiveEnabled(context: Context, packageName: String): Boolean =
        GameExtras.preload(context, packageName) ?: isEnabled(context)

    fun budgetMb(context: Context): Int = prefs(context)
        .getInt(SpfConfig.GLOBAL_SPF_GAME_PRELOAD_MB, 256)
        .coerceIn(32, 2048)

    fun shouldPreload(context: Context, packageName: String, appModeActive: Boolean): Boolean =
        GamePreloadPolicy.shouldPreload(
            enabled = effectiveEnabled(context, packageName),
            appModeActive = appModeActive,
            engineOff = ProfileController.isEngineOff(context),
            trueOff = TrueOff.isOff(context),
            rootAvailable = CheckRootStatus.isAvailable()
        )

    /** Fire-and-forget preload for [packageName]; safe from any thread. */
    fun preload(context: Context, packageName: String) {
        if (!shouldPreload(context, packageName, true)) return
        synchronized(lastPreloaded) {
            val last = lastPreloaded[packageName] ?: 0L
            if (System.currentTimeMillis() - last < RATE_LIMIT_MS) return
            lastPreloaded[packageName] = System.currentTimeMillis()
        }
        val app = context.applicationContext
        val budgetBytes = budgetMb(app).toLong() * 1024 * 1024
        Thread {
            try {
                val script = buildString {
                    appendLine("apk=\$(pm path '$packageName' 2>/dev/null | head -n1 | cut -d: -f2)")
                    appendLine("[ -z \"\$apk\" ] && exit 0")
                    appendLine("lib=\"\${apk%/*}/lib/arm64\"")
                    appendLine("[ -d \"\$lib\" ] || lib=\"\${apk%/*}/lib/arm\"")
                    appendLine("[ -d \"\$lib\" ] || exit 0")
                    appendLine("total=0")
                    appendLine("for f in \"\$lib\"/*.so; do")
                    appendLine("  [ -f \"\$f\" ] || continue")
                    appendLine("  sz=\$(wc -c < \"\$f\" 2>/dev/null || echo 0)")
                    appendLine("  total=\$((total + sz))")
                    appendLine("  [ \"\$total\" -gt $budgetBytes ] && break")
                    appendLine("  cat \"\$f\" > /dev/null 2>&1")
                    appendLine("done")
                    appendLine("echo \"preloaded \$total bytes\"")
                }
                val out = RootShell.run(script).trim()
                ShellLog.log("GamePreload", "$packageName ${out.take(80)}")
            } catch (ex: Exception) {
                ShellLog.log("GamePreload", ex.message ?: "error", error = true)
            }
        }.start()
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
}
