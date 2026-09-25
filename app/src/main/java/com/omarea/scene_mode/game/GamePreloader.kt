package com.omarea.scene_mode.game

import android.content.Context
import com.omarea.common.shared.FileWrite
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.ShellEscape
import com.omarea.utils.SceneLog
import com.omarea.vtools.SceneJNI
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Warms the page cache of a game's native libraries a few seconds after it
 * starts, the way AZenith's preload step does (concept adapted, Apache-2.0).
 */
object GamePreloader {
    private const val START_DELAY_MS = 5000L
    private const val RETRIGGER_GUARD_MS = 10L * 60L * 1000L

    private val lastPreload = ConcurrentHashMap<String, Long>()

    @Volatile
    private var scriptPath: String? = null

    @OptIn(DelicateCoroutinesApi::class)
    fun preload(context: Context, packageName: String, budgetMb: Int) {
        if (packageName.isEmpty()) {
            return
        }
        val now = System.currentTimeMillis()
        val previous = lastPreload[packageName] ?: 0L
        if (now - previous < RETRIGGER_GUARD_MS) {
            return
        }
        lastPreload[packageName] = now

        GlobalScope.launch(Dispatchers.IO) {
            try {
                // Give the game time to finish spawning before touching its files.
                delay(START_DELAY_MS)

                val target = resolvePreloadDir(context, packageName)
                val touched = if (target != null) {
                    try {
                        SceneJNI().preloadPath(target, budgetMb.toLong())
                    } catch (t: Throwable) {
                        -1L
                    }
                } else {
                    -1L
                }
                if (touched >= 0) {
                    SceneLog.i("GamePreload", "$packageName: touched $touched bytes from $target")
                    return@launch
                }

                // Fallback: shell based warm-up when the native helper is unavailable.
                val script = scriptPath ?: FileWrite.writePrivateShellFile(
                    "addin/game_preload.sh",
                    "addin/game_preload.sh",
                    context
                )?.also { scriptPath = it } ?: return@launch

                val result = KeepShellPublic.doCmdSync(
                    "sh " + ShellEscape.cmd(script, packageName, budgetMb.toString())
                )
                SceneLog.i("GamePreload", packageName + ": " + result.trim())
            } catch (ex: Exception) {
                SceneLog.e("GamePreload", "preload failed for $packageName", ex)
            }
        }
    }

    private fun resolvePreloadDir(context: Context, packageName: String): String? {
        return try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            when {
                info.nativeLibraryDir != null && File(info.nativeLibraryDir).isDirectory -> info.nativeLibraryDir
                info.sourceDir != null -> File(info.sourceDir).parent
                else -> null
            }
        } catch (ex: Exception) {
            null
        }
    }
}
