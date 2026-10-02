package com.omarea.runtime

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import com.omarea.common.shell.ShellLog
import com.omarea.data.SpfConfig
import com.omarea.engine.TuningRepository
import com.omarea.util.PlatformUtils
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full configuration backup / restore (AZenith-derived idea,
 * docs/ATTRIBUTION.md).
 *
 * One JSON file under `/sdcard/Scene/backup/` carrying:
 *  - the tuning user copy (when present),
 *  - the per-app mode map,
 *  - the Scene feature keys (engine/mode/dynamic control/saver/DND/priority/
 *    preload/bypass…; TRUE OFF and root state are deliberately excluded),
 *  - the per-app refresh and downscale maps.
 *
 * `latest.json` always mirrors the newest backup so restore is one tap.
 * Blocking file I/O — call off the main thread.
 *
 * Responsibility: read/write the backup file.
 * Non-goals: applying tuning (engine reads the files it always reads).
 */
object ConfigBackup {

    private const val VERSION = 1

    /** Scene feature keys worth carrying; TRUE OFF / root state stay out. */
    private val GLOBAL_KEYS = listOf(
        SpfConfig.GLOBAL_SPF_LAST_MODE,
        SpfConfig.GLOBAL_SPF_PROFILE_OFF,
        SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL,
        SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL_DELAY,
        SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL_STRICT,
        SpfConfig.GLOBAL_SPF_POWERCFG_FIRST_MODE,
        SpfConfig.GLOBAL_SPF_POWERCFG_SLEEP_MODE,
        SpfConfig.GLOBAL_SPF_SAVER_OVERLAY_ENABLED,
        SpfConfig.GLOBAL_SPF_DND_APP_MODE,
        SpfConfig.GLOBAL_SPF_GAME_PRIORITY,
        SpfConfig.GLOBAL_SPF_GAME_PRELOAD,
        SpfConfig.GLOBAL_SPF_GAME_PRELOAD_MB,
        SpfConfig.GLOBAL_SPF_BYPASS_CHARGE,
        SpfConfig.GLOBAL_SPF_BYPASS_CHARGE_THRESHOLD,
        SpfConfig.GLOBAL_SPF_BYPASS_CHARGE_MODE,
        SpfConfig.GLOBAL_SPF_DISPLAY_RESTART,
        SpfConfig.GLOBAL_SPF_REDUCE_LOGGING,
        SpfConfig.GLOBAL_SPF_KERNEL_CRASH_GUARD,
        SpfConfig.GLOBAL_SPF_SF_PACING,
        SpfConfig.GLOBAL_SPF_DIRECT_WRITES
    )

    data class Result(val ok: Boolean, val message: String)

    fun dir(): File = File(Environment.getExternalStorageDirectory(), "Scene/backup")

    fun latestFile(): File = File(dir(), "latest.json")

    /** Writes a timestamped backup + `latest.json`. */
    fun backup(context: Context): Result = try {
        val app = context.applicationContext
        val platform = PlatformUtils().getCPUName()
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("created_at", System.currentTimeMillis())
        root.put("platform", platform)
        TuningRepository.readUserText(platform)?.let { root.put("tuning", it) }
        root.put("powercfg", jsonOf(prefs(app, SpfConfig.POWER_CONFIG_SPF).all))
        root.put("global", jsonOf(prefs(app, SpfConfig.GLOBAL_SPF).all, GLOBAL_KEYS.toSet()))
        root.put("refresh", jsonOf(prefs(app, "powercfg_refresh").all))
        root.put("display", jsonOf(prefs(app, "powercfg_display").all))

        val outDir = dir().apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(outDir, "scene-backup-$stamp.json")
        file.writeText(root.toString(2))
        latestFile().writeText(root.toString(2))
        ShellLog.log("ConfigBackup", "backup -> ${file.name}")
        Result(true, file.absolutePath)
    } catch (ex: Exception) {
        ShellLog.log("ConfigBackup", ex.message ?: "backup error", error = true)
        Result(false, ex.message ?: "backup error")
    }

    /** Restores the newest backup (`latest.json`). */
    fun restoreLatest(context: Context): Result = try {
        val file = latestFile()
        if (!file.isFile) return Result(false, "no backup found")
        val root = JSONObject(file.readText())
        val app = context.applicationContext
        val platform = PlatformUtils().getCPUName()

        root.optString("tuning").takeIf { it.isNotEmpty() }?.let {
            if (!TuningRepository.writeUser(platform, it)) {
                return Result(false, "cannot write tuning user copy")
            }
        }
        restorePrefs(app, SpfConfig.POWER_CONFIG_SPF, root.optJSONObject("powercfg"))
        restorePrefs(app, SpfConfig.GLOBAL_SPF, root.optJSONObject("global"), GLOBAL_KEYS.toSet())
        restorePrefs(app, "powercfg_refresh", root.optJSONObject("refresh"))
        restorePrefs(app, "powercfg_display", root.optJSONObject("display"))

        ShellLog.log("ConfigBackup", "restore <- ${file.name}")
        Result(true, file.absolutePath)
    } catch (ex: Exception) {
        ShellLog.log("ConfigBackup", ex.message ?: "restore error", error = true)
        Result(false, ex.message ?: "restore error")
    }

    private fun jsonOf(map: Map<String, *>, allow: Set<String>? = null): JSONObject {
        val obj = JSONObject()
        for ((key, value) in map) {
            if (allow != null && key !in allow) continue
            when (value) {
                is Boolean, is Int, is Long, is String -> obj.put(key, value)
                is Float -> obj.put(key, value.toDouble())
                is Double -> obj.put(key, value)
                else -> Unit // sets are not used by these prefs
            }
        }
        return obj
    }

    private fun restorePrefs(
        context: Context,
        name: String,
        obj: JSONObject?,
        allow: Set<String>? = null
    ) {
        if (obj == null) return
        val edit = prefs(context, name).edit()
        for (key in obj.keys()) {
            if (allow != null && key !in allow) continue
            val value = obj.get(key)
            when (value) {
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Double -> edit.putFloat(key, value.toFloat())
                is String -> edit.putString(key, value)
            }
        }
        edit.apply()
    }

    private fun prefs(context: Context, name: String): SharedPreferences =
        context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
}
