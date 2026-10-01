package com.omarea.engine

import android.content.Context
import com.omarea.common.shell.ShellLog
import com.omarea.util.PlatformUtils
import org.json.JSONObject
import java.io.File

/**
 * Android-side store for profile edits: reads the tuning-JSON pair
 * (user copy + shipped preset), applies an edit and writes the user copy
 * atomically with a `.bak` backup of the previous revision.
 *
 * Responsibility: file I/O around [ProfileDoc].
 * Non-goals: editing semantics (pure [ProfileDoc]) or applying tuning.
 */
object ProfileStore {

    /** Effective doc, or null when neither the user copy nor the asset exists. */
    fun doc(context: Context): ProfileDoc? = try {
        val platform = PlatformUtils().getCPUName()
        val presetText = runCatching { TuningRepository.bundledText(context, platform) }.getOrNull()
        val userText = TuningRepository.readUserText(platform)
        when {
            presetText != null -> ProfileDoc.parse(userText, presetText)
            userText != null -> ProfileDoc.parse(userText, userText)
            else -> null
        }
    } catch (ex: Exception) {
        ShellLog.log("ProfileStore.doc", ex.message ?: "error", error = true)
        null
    }

    /** Effective profile for [mode] (copy), or null when the doc is unavailable. */
    fun load(context: Context, mode: String): JSONObject? =
        doc(context)?.effectiveProfile(mode)

    fun isModified(context: Context, mode: String): Boolean =
        doc(context)?.isModified(mode) ?: false

    fun modifiedPaths(context: Context, mode: String): List<String> =
        doc(context)?.modifiedPaths(mode).orEmpty()

    fun summary(context: Context, mode: String): String =
        doc(context)?.summary(mode, DeviceCaps.POLICIES).orEmpty()

    /** Writes [profile] into the user copy of [mode]. */
    fun save(context: Context, mode: String, profile: JSONObject): Result<Unit> =
        write(context) { it.withProfile(mode, profile) }

    /** Restores the shipped preset of [mode] into the user copy. */
    fun resetToPreset(context: Context, mode: String): Result<Unit> =
        write(context) { it.resetToPreset(mode) }

    private fun write(context: Context, transform: (ProfileDoc) -> ProfileDoc): Result<Unit> = try {
        val platform = PlatformUtils().getCPUName()
        val doc = doc(context) ?: throw IllegalStateException("tuning.json not found")
        transform(doc)
        backup(platform)
        if (!TuningRepository.writeUser(platform, doc.userText())) {
            throw IllegalStateException("cannot write ${TuningRepository.userFile(platform).absolutePath}")
        }
        Result.success(Unit)
    } catch (ex: Exception) {
        ShellLog.log("ProfileStore.write", ex.message ?: "error", error = true)
        Result.failure(ex)
    }

    private fun backup(platform: String) {
        runCatching {
            val file = TuningRepository.userFile(platform)
            if (!file.isFile) return@runCatching
            file.copyTo(File(file.parentFile, "${file.name}.bak"), overwrite = true)
        }
    }
}
