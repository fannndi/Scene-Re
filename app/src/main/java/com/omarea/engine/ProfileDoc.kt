package com.omarea.engine

import org.json.JSONObject

/**
 * Pure document over the tuning-JSON pair: the user copy (editable) and the
 * shipped preset (read-only reference).
 *
 * The editor loads the *effective* profile (user profile when present, else
 * the preset of that id), edits it, and writes it back into the user copy.
 * Only the edited profile object is replaced — every other key of the user
 * document is preserved, so editing one profile can never drop another.
 *
 * Responsibility: profile lookup, edit, reset and diff over parsed JSON.
 * Non-goals: file I/O ([ProfileStore]) or applying tuning ([ProfileController]).
 * Invariants:
 *  - [effectiveProfile] always returns a copy; callers cannot mutate the doc.
 *  - Saved profiles are stored under the canonical [ProfileKey] id.
 */
class ProfileDoc private constructor(
    private val user: JSONObject,
    private val preset: JSONObject
) {

    /** Effective profile for [mode]: user copy first, preset when absent/empty. */
    fun effectiveProfile(mode: String): JSONObject {
        val userProfile = userProfile(mode)
        if (userProfile != null && userProfile.length() > 0) return deepCopy(userProfile)
        val presetProfile = presetProfile(mode)
        if (presetProfile != null) return deepCopy(presetProfile)
        return JSONObject()
    }

    fun userProfile(mode: String): JSONObject? =
        ProfileKey.profile(user.optJSONObject("profiles"), mode)

    fun presetProfile(mode: String): JSONObject? =
        ProfileKey.profile(preset.optJSONObject("profiles"), mode)

    /** Changed key paths of [mode] vs its shipped preset (empty = untouched). */
    fun modifiedPaths(mode: String): List<String> =
        ProfileDiff.changedPaths(userProfile(mode), presetProfile(mode))

    fun isModified(mode: String): Boolean = modifiedPaths(mode).isNotEmpty()

    /** Row summary for the profile card ("1.32 / 1.32 GHz · UFS save"). */
    fun summary(mode: String, policies: List<String>): String =
        ProfileSummary.summarize(effectiveProfile(mode), policies)

    /** Replaces (or creates) the profile object for [mode]; returns this doc. */
    fun withProfile(mode: String, profile: JSONObject): ProfileDoc {
        val canonical = ProfileKey.canonical(mode)
        val profiles = user.optJSONObject("profiles")
            ?: JSONObject().also { user.put("profiles", it) }
        profiles.put(canonical, deepCopy(profile))
        if (canonical == ProfileKey.CUSTOM) profiles.remove(ProfileKey.LEGACY_FAST)
        return this
    }

    /** Restores the shipped preset of [mode] into the user copy. */
    fun resetToPreset(mode: String): ProfileDoc {
        presetProfile(mode)?.let { withProfile(mode, it) }
        return this
    }

    /** Serialized user document (pretty printed). */
    fun userText(): String = user.toString(4)

    companion object {
        /**
         * Builds the doc from raw JSON text. When the user copy is missing or
         * unparsable the preset doubles as the base document, so the first save
         * materializes a complete user copy.
         */
        fun parse(userText: String?, presetText: String): ProfileDoc {
            val preset = JSONObject(presetText)
            val user = userText
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: JSONObject(preset.toString())
            return ProfileDoc(user, preset)
        }

        fun deepCopy(obj: JSONObject): JSONObject = JSONObject(obj.toString())
    }
}
