package com.omarea.scene_mode.game

import android.content.Context
import com.omarea.utils.SceneLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the per-game session summaries recorded by [GameSessionTracker].
 * Stored as JSON in the app's private directory; the newest entry comes first
 * and the list is capped so the file cannot grow without bound.
 */
object GameSessionStore {
    private const val FILE_NAME = "game_sessions.json"
    private const val MAX_ENTRIES = 40

    data class GameSession(
        val packageName: String,
        val startedAt: Long,
        val endedAt: Long,
        val samples: Int,
        val startLevel: Int,
        val endLevel: Int,
        val maxTempC: Double,
        /** Highest MIUI thermal state seen (0 = node absent). */
        val maxTempState: Int = 0,
        val avgFps: Double,
        val modes: String,
        val guardActivations: Int
    )

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun list(context: Context): List<GameSession> {
        return try {
            val f = file(context)
            if (!f.isFile) {
                return emptyList()
            }
            val array = JSONArray(f.readText())
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                GameSession(
                    packageName = obj.optString("package"),
                    startedAt = obj.optLong("startedAt"),
                    endedAt = obj.optLong("endedAt"),
                    samples = obj.optInt("samples"),
                    startLevel = obj.optInt("startLevel", -1),
                    endLevel = obj.optInt("endLevel", -1),
                    maxTempC = obj.optDouble("maxTempC", 0.0),
                    maxTempState = obj.optInt("maxTempState", 0),
                    avgFps = obj.optDouble("avgFps", 0.0),
                    modes = obj.optString("modes"),
                    guardActivations = obj.optInt("guard")
                )
            }
        } catch (ex: Exception) {
            SceneLog.e("GameSessionStore", "failed to read sessions", ex)
            emptyList()
        }
    }

    fun append(context: Context, session: GameSession) {
        try {
            val entries = (listOf(session) + list(context)).take(MAX_ENTRIES)
            val array = JSONArray()
            entries.forEach { entry ->
                array.put(
                    JSONObject()
                        .put("package", entry.packageName)
                        .put("startedAt", entry.startedAt)
                        .put("endedAt", entry.endedAt)
                        .put("samples", entry.samples)
                        .put("startLevel", entry.startLevel)
                        .put("endLevel", entry.endLevel)
                        .put("maxTempC", entry.maxTempC)
                        .put("maxTempState", entry.maxTempState)
                        .put("avgFps", entry.avgFps)
                        .put("modes", entry.modes)
                        .put("guard", entry.guardActivations)
                )
            }
            file(context).writeText(array.toString())
        } catch (ex: Exception) {
            SceneLog.e("GameSessionStore", "failed to save session", ex)
        }
    }

    fun clear(context: Context) {
        try {
            file(context).delete()
        } catch (ex: Exception) {
            SceneLog.e("GameSessionStore", "failed to clear sessions", ex)
        }
    }
}
