package com.omarea.scene_mode.game

import android.content.Context
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.ShellEscape
import com.omarea.scene_mode.ModeSwitcher
import com.omarea.store.CpuConfigStorage
import com.omarea.utils.SceneLog

/**
 * Per-game profiles.
 *
 * Every game resolves to a mode through three layers, most specific first:
 *
 *  1. a user override (`/data/adb/scene/game_profiles.txt`, `package=profile`),
 *  2. the learned class (`/data/adb/scene/game_profiles_learned.txt`,
 *     `package=light|heavy`) written by [GameProfiler] when a session was
 *     decisive,
 *  3. the safe default: performance.
 *
 * The automatic rule maps a heavy game to Performance and a light one to the
 * Custom profile when one was saved from CPU Control, otherwise to the bundled
 * Light profile. The result is materialised into
 * `/data/adb/scene/game_profiles_effective.txt` (`package=mode`) so the
 * app_process monitor can resolve the same profile without the app's assets.
 *
 * The pure helpers are shared with the monitor; only the accessors below touch
 * the root shell.
 */
object GameProfileStore {
    const val OVERRIDE_FILE = "/data/adb/scene/game_profiles.txt"
    const val LEARNED_FILE = "/data/adb/scene/game_profiles_learned.txt"
    const val EFFECTIVE_FILE = "/data/adb/scene/game_profiles_effective.txt"
    const val CUSTOM_READY_PROP = "vtools.scene.custom.ready"
    const val LIGHT_READY_PROP = "vtools.scene.light.ready"

    /** No override: the automatic rule applies. */
    const val AUTO = ""
    const val PERFORMANCE = "performance"
    const val CUSTOM = "custom"
    const val LIGHT = "light"
    const val BALANCE = "balance"
    const val POWERSAVE = "powersave"
    const val OFF = "off"

    /** Do not switch modes for this game, only apply its options. */
    const val KEEP = "keep"

    const val CLASS_HEAVY = GameProfiler.CLASS_HEAVY
    const val CLASS_LIGHT = GameProfiler.CLASS_LIGHT

    @Volatile
    private var overrides: Map<String, String>? = null

    @Volatile
    private var overridesAt = 0L

    @Volatile
    private var learned: Map<String, String>? = null

    @Volatile
    private var learnedAt = 0L

    /** The app and the monitor both write the files; keep the cache short. */
    private const val CACHE_MS = 5_000L

    fun invalidate() {
        overrides = null
        learned = null
    }

    // +---------------------------------------------------------------+
    // | Pure helpers (also used by the app_process monitor)            |
    // +---------------------------------------------------------------+

    fun parseProfiles(text: String): Map<String, String> {
        val result = HashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) {
                continue
            }
            val index = line.indexOf('=')
            if (index <= 0 || index == line.length - 1) {
                continue
            }
            result[line.substring(0, index).trim()] = line.substring(index + 1).trim()
        }
        return result
    }

    fun renderProfiles(entries: Map<String, String>): String =
        entries.entries
            .filter { it.key.isNotEmpty() && it.value.isNotEmpty() }
            .sortedBy { it.key }
            .joinToString("\n") { "${it.key}=${it.value}" }

    /** A stored profile name to the mode the engine executes. */
    fun modeFor(profile: String): String = when (profile) {
        PERFORMANCE -> ModeSwitcher.PERFORMANCE
        CUSTOM -> ModeSwitcher.FAST
        LIGHT -> ModeSwitcher.LIGHT
        BALANCE -> ModeSwitcher.BALANCE
        POWERSAVE -> ModeSwitcher.POWERSAVE
        OFF -> ModeSwitcher.OFF
        KEEP -> KEEP
        else -> AUTO
    }

    /**
     * Resolve one package. [override] is the raw user choice ([AUTO] when
     * absent), [learnedClass] the observed class and [customReady] whether a
     * Custom configuration exists.
     */
    fun resolve(override: String, learnedClass: String, customReady: Boolean): String {
        when (override) {
            PERFORMANCE, CUSTOM, LIGHT, BALANCE, POWERSAVE, OFF, KEEP -> return override
        }
        return when (learnedClass) {
            CLASS_LIGHT -> if (customReady) CUSTOM else LIGHT
            else -> PERFORMANCE
        }
    }

    // +---------------------------------------------------------------+
    // | App-side accessors                                             |
    // +---------------------------------------------------------------+

    private fun readFile(path: String): Map<String, String> {
        return try {
            parseProfiles(
                KeepShellPublic.doCmdSync("cat " + ShellEscape.quote(path) + " 2> /dev/null")
            )
        } catch (ex: Exception) {
            SceneLog.e("GameProfileStore", "failed to read $path", ex)
            emptyMap()
        }
    }

    private fun writeFile(path: String, body: String) {
        KeepShellPublic.doCmdSync(
            "mkdir -p /data/adb/scene\n" +
                "cat > " + ShellEscape.quote(path) + " << 'SCENE_GAME_PROFILE_EOF'\n" +
                body + "\nSCENE_GAME_PROFILE_EOF"
        )
    }

    fun overrides(): Map<String, String> {
        val cached = overrides
        if (cached != null && System.currentTimeMillis() - overridesAt < CACHE_MS) {
            return cached
        }
        val loaded = readFile(OVERRIDE_FILE)
        overrides = loaded
        overridesAt = System.currentTimeMillis()
        return loaded
    }

    fun learned(): Map<String, String> {
        val cached = learned
        if (cached != null && System.currentTimeMillis() - learnedAt < CACHE_MS) {
            return cached
        }
        val loaded = readFile(LEARNED_FILE)
        learned = loaded
        learnedAt = System.currentTimeMillis()
        return loaded
    }

    fun overrideFor(packageName: String): String? = overrides()[packageName]

    /** The observed class, or "" while the game has not been classified yet. */
    fun classOf(packageName: String): String = learned()[packageName] ?: ""

    fun customReady(context: Context): Boolean = CpuConfigStorage(context).exists(ModeSwitcher.FAST)

    /** Only the bundled providers ship the `light` action. */
    private fun lightProfileSupported(): Boolean {
        return when (ModeSwitcher.getCurrentSource()) {
            ModeSwitcher.SOURCE_NONE,
            ModeSwitcher.SOURCE_SCENE_ACTIVE,
            ModeSwitcher.SOURCE_SCENE_CONSERVATIVE -> true
            else -> false
        }
    }

    /** The mode a game should run; [KEEP] leaves the current mode alone. */
    fun modeFor(context: Context, packageName: String): String {
        if (packageName.isEmpty()) {
            return ""
        }
        val override = overrideFor(packageName) ?: AUTO
        val resolved = resolve(override, classOf(packageName), customReady(context))
        if (resolved == LIGHT && !lightProfileSupported()) {
            // An external or imported provider has no `light` action; balance
            // is the closest safe profile and the light caps still apply.
            return BALANCE
        }
        return resolved
    }

    fun setOverride(context: Context, packageName: String, profile: String) {
        if (packageName.isEmpty()) {
            return
        }
        val updated = overrides().toMutableMap()
        if (profile.isEmpty() || profile == AUTO) {
            updated.remove(packageName)
        } else {
            updated[packageName] = profile
        }
        writeFile(OVERRIDE_FILE, renderProfiles(updated))
        overrides = updated
        overridesAt = System.currentTimeMillis()
        GameProfiler.reset(packageName)
        materialize(context)
    }

    fun setClass(context: Context, packageName: String, learnedClass: String) {
        if (packageName.isEmpty() || learnedClass.isEmpty()) {
            return
        }
        val updated = learned().toMutableMap()
        updated[packageName] = learnedClass
        writeFile(LEARNED_FILE, renderProfiles(updated))
        learned = updated
        learnedAt = System.currentTimeMillis()
        materialize(context)
    }

    /** Forget the observed class so the next session classifies again. */
    fun clearClass(context: Context, packageName: String) {
        val updated = learned().toMutableMap()
        if (updated.remove(packageName) == null) {
            return
        }
        writeFile(LEARNED_FILE, renderProfiles(updated))
        learned = updated
        learnedAt = System.currentTimeMillis()
        GameProfiler.reset(packageName)
        materialize(context)
    }

    /** Forget every observed class (the user overrides stay untouched). */
    fun clearAllClasses(context: Context) {
        if (learned().isEmpty()) {
            return
        }
        writeFile(LEARNED_FILE, "")
        learned = emptyMap()
        learnedAt = System.currentTimeMillis()
        materialize(context)
    }

    /**
     * Write the effective map for the fallback monitor and publish whether a
     * Custom configuration exists (the monitor has no access to the app's
     * private object storage).
     */
    fun materialize(context: Context) {
        try {
            val custom = customReady(context)
            val result = HashMap<String, String>()
            for ((packageName, learnedClass) in learned()) {
                result[packageName] = resolve(overrides()[packageName] ?: AUTO, learnedClass, custom)
            }
            for ((packageName, profile) in overrides()) {
                result[packageName] = resolve(profile, learned()[packageName] ?: "", custom)
            }
            writeFile(EFFECTIVE_FILE, renderProfiles(result))
            KeepShellPublic.doCmdSync(
                "setprop $CUSTOM_READY_PROP " + (if (custom) "1" else "0") + "\n" +
                    "setprop $LIGHT_READY_PROP " + (if (lightProfileSupported()) "1" else "0")
            )
        } catch (ex: Exception) {
            SceneLog.e("GameProfileStore", "materialize failed", ex)
        }
    }
}
