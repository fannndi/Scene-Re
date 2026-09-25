package com.omarea.scene_mode.monitor

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import com.omarea.scene_mode.game.GameListStore
import com.omarea.scene_mode.game.GameProfileStore
import com.omarea.scene_mode.game.GameProfiler
import com.omarea.scene_mode.ModeSwitcher

/**
 * Foreground monitor companion, meant to be started with `app_process` as root:
 *
 *   app_process -Djava.class.path=<apk> / --nice-name=sys.scene-monitor \
 *       com.omarea.scene_mode.monitor.SystemMonitor <status> <games> <globalPrefs> \
 *       <appPrefs> <switchSh> <optionsSh> <boostSh> <gameMode> [profiles] [intervalMs]
 *
 * It exists so the automatic game profile still works when the accessibility
 * service is unavailable (HyperOS and some ROMs kill it aggressively). Unlike
 * AZenith's monitor it does not use any hidden-API bypass: the foreground
 * package comes from a `dumpsys` probe, everything else from sysfs.
 *
 * While the accessibility service is running (`vtools.scene.accessibility=1`)
 * the monitor only records the status; the app owns the switching.
 *
 * The per-game profile comes from the effective map the app materialises
 * (`/data/adb/scene/game_profiles_effective.txt`). The monitor also runs the
 * same [GameProfiler] as the app, so a light game is downgraded even when only
 * the monitor is alive.
 */
object SystemMonitor {
    private const val DEFAULT_INTERVAL_MS = 3000L
    private const val PROP_ACCESSIBILITY = "vtools.scene.accessibility"
    private const val PROP_CUSTOM_READY = GameProfileStore.CUSTOM_READY_PROP
    private const val PROP_LIGHT_READY = GameProfileStore.LIGHT_READY_PROP
    private const val FPS_NODE = "/sys/class/drm/sde-crtc-0/measured_fps"
    private const val GPU_BUSY_NODES = "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage /sys/class/kgsl/kgsl-3d0/gpuload /sys/class/kgsl/kgsl-3d0/devfreq/gpu_load"

    @JvmStatic
    fun main(args: Array<String>) {
        val statusFile = args.getOrNull(0) ?: return
        val gamesFile = args.getOrNull(1) ?: GameListStore.effectiveFilePath()
        val globalPrefs = args.getOrNull(2) ?: ""
        val appPrefs = args.getOrNull(3) ?: ""
        val powercfgSh = args.getOrNull(4) ?: ""
        val optionsSh = args.getOrNull(5) ?: ""
        val boostSh = args.getOrNull(6) ?: ""
        val gameMode = args.getOrNull(7) ?: ModeSwitcher.PERFORMANCE
        val profilesFile = args.getOrNull(8) ?: GameProfileStore.EFFECTIVE_FILE
        val interval = args.getOrNull(9)?.toLongOrNull() ?: DEFAULT_INTERVAL_MS

        var lastPackage = ""
        var games = readLines(gamesFile)
        var profiles = readProfiles(profilesFile)
        var tick = 0L

        while (true) {
            try {
                tick++
                val owned = shell("getprop $PROP_ACCESSIBILITY").trim() != "1"
                val foreground = foregroundPackage()
                if (foreground != lastPackage) {
                    lastPackage = foreground
                    games = readLines(gamesFile)
                    profiles = readProfiles(profilesFile)
                    if (foreground.isNotEmpty() && owned) {
                        if (games.contains(foreground)) {
                            enterGame(
                                foreground, profiles, gameMode,
                                globalPrefs, appPrefs, powercfgSh, optionsSh, boostSh
                            )
                        } else if (activeGame.isNotEmpty()) {
                            leaveGame(globalPrefs, appPrefs, powercfgSh, optionsSh, boostSh)
                        }
                    } else if (!owned && activeGame.isNotEmpty()) {
                        // The accessibility service took over mid-session.
                        resetGameState()
                    }
                    writeStatus(statusFile, foreground)
                }
                // Battery saver follow: the same rule as the app-side
                // BatterySaverFollow, so the feature works in both control
                // paths instead of only with accessibility enabled.
                followBatterySaver(
                    foreground, games, globalPrefs, appPrefs,
                    powercfgSh, optionsSh, boostSh
                )
                if (owned && activeGame.isNotEmpty() && tick % 3L == 0L) {
                    learnActiveGame(profilesFile, globalPrefs, appPrefs, powercfgSh, optionsSh, boostSh)
                }
            } catch (ex: Exception) {
                // Never let a probe failure kill the loop.
            }
            Thread.sleep(interval)
        }
    }

    /** Game currently applied by this monitor ("" when none). */
    private var activeGame = ""

    /** Mode recorded before the game started ("" when it was not changed). */
    private var previousMode = ""

    /** Light-game state of the active game, refreshed on entry and learning. */
    private var gameLight = false

    /** Light-game options, refreshed on every game entry. */
    private var lightDetect = true
    private var lightCpuLimit = 70
    private var lightGpuLimit = 60

    private fun resetGameState() {
        activeGame = ""
        previousMode = ""
        gameLight = false
    }

    /** Apply a game's resolved profile plus its options. */
    private fun enterGame(
        game: String,
        profiles: Map<String, String>,
        defaultMode: String,
        globalPrefs: String,
        appPrefs: String,
        powercfgSh: String,
        optionsSh: String,
        boostSh: String
    ) {
        if (powercfgSh.isEmpty() || !File(powercfgSh).exists()) {
            return
        }
        val current = shell("getprop vtools.powercfg").trim()
        val stored = profiles[game]
        var mode = if (stored != null) GameProfileStore.modeFor(stored) else defaultMode
        if (mode.isEmpty() || mode == GameProfileStore.AUTO) {
            mode = defaultMode
        }
        if (mode == GameProfileStore.KEEP) {
            mode = current.ifEmpty { defaultMode }
        }
        previousMode = if (current.isNotEmpty() && current != mode) current else ""
        activeGame = game

        val prefs = readPrefs(globalPrefs)
        lightDetect = prefs["profile_light_detect"]?.toBoolean() ?: true
        lightCpuLimit = prefs["profile_light_cpu_limit"]?.toIntOrNull() ?: 70
        lightGpuLimit = prefs["profile_light_gpu_limit"]?.toIntOrNull() ?: 60
        gameLight = readProfiles(GameProfileStore.LEARNED_FILE)[game] == GameProfiler.CLASS_LIGHT

        shell("sh " + quote(powercfgSh) + " " + quote(mode) + " " + quote(game))
        applyGameOptions(game, mode, globalPrefs, appPrefs, optionsSh, boostSh)
        SceneStatus.write(mode, game, true, ::shell)
    }

    /** Leave the game: restore the previous mode and drop the game options. */
    private fun leaveGame(
        globalPrefs: String,
        appPrefs: String,
        powercfgSh: String,
        optionsSh: String,
        boostSh: String
    ) {
        val mode = ModeSwitcher.getCurrentPowerMode()
        if (previousMode.isNotEmpty() && powercfgSh.isNotEmpty()) {
            shell("sh " + quote(powercfgSh) + " " + quote(previousMode))
        }
        val env = optionsEnvironment(globalPrefs, appPrefs, "", mode, false) +
            "export SCENE_GAME_RESET=1\n"
        if (optionsSh.isNotEmpty() && File(optionsSh).exists()) {
            shell(env + "sh " + quote(optionsSh))
        }
        if (boostSh.isNotEmpty() && File(boostSh).exists()) {
            shell(env + "sh " + quote(boostSh))
        }
        SceneStatus.write(if (previousMode.isNotEmpty()) previousMode else mode, "", false, ::shell)
        resetGameState()
    }

    /**
     * Run the shared profiler for the active game. A decisive window is
     * persisted and, when the user did not pin a profile, the resolved mode is
     * applied right away.
     */
    private fun learnActiveGame(
        profilesFile: String,
        globalPrefs: String,
        appPrefs: String,
        powercfgSh: String,
        optionsSh: String,
        boostSh: String
    ) {
        if (!lightDetect) {
            return
        }
        val decision = GameProfiler.observe(activeGame, gpuBusyPercent(), measuredFps()) ?: return
        val learned = readProfiles(GameProfileStore.LEARNED_FILE).toMutableMap()
        if (learned[activeGame] == decision) {
            return
        }
        learned[activeGame] = decision
        writeFile(GameProfileStore.LEARNED_FILE, GameProfileStore.renderProfiles(learned))

        val customReady = shell("getprop $PROP_CUSTOM_READY").trim() == "1"
        val override = readProfiles(GameProfileStore.OVERRIDE_FILE)[activeGame] ?: GameProfileStore.AUTO
        var resolved = GameProfileStore.resolve(override, decision, customReady)
        if (resolved == GameProfileStore.LIGHT && shell("getprop $PROP_LIGHT_READY").trim() != "1") {
            // The provider has no `light` action (external/imported config).
            resolved = GameProfileStore.BALANCE
        }

        val effective = readProfiles(profilesFile).toMutableMap()
        effective[activeGame] = resolved
        writeFile(profilesFile, GameProfileStore.renderProfiles(effective))

        gameLight = decision == GameProfiler.CLASS_LIGHT
        val mode = GameProfileStore.modeFor(resolved)
        val current = shell("getprop vtools.powercfg").trim()
        if (override != GameProfileStore.AUTO || mode == GameProfileStore.KEEP ||
            mode.isEmpty() || mode == current
        ) {
            return
        }
        shell("sh " + quote(powercfgSh) + " " + quote(mode) + " " + quote(activeGame))
        applyGameOptions(activeGame, mode, globalPrefs, appPrefs, optionsSh, boostSh)
        SceneStatus.write(mode, activeGame, true, ::shell)
    }

    private fun applyGameOptions(
        game: String,
        mode: String,
        globalPrefs: String,
        appPrefs: String,
        optionsSh: String,
        boostSh: String
    ) {
        if (optionsSh.isEmpty() && boostSh.isEmpty()) {
            return
        }
        val disabled = readPrefs(appPrefs)["$game.enabled"]?.toIntOrNull() == 0
        if (disabled) {
            if (optionsSh.isNotEmpty() && File(optionsSh).exists()) {
                shell(
                    "export SCENE_QCOM_BUS=0; export SCENE_QCOM_GPU=0; export SCENE_QCOM_GPU_PS=0;" +
                        " export SCENE_RESET=1; sh " + quote(optionsSh)
                )
            }
            if (boostSh.isNotEmpty() && File(boostSh).exists()) {
                shell("SCENE_QCOM_BUS=0 SCENE_QCOM_GPU=0 SCENE_QCOM_GPU_PS=0 sh " + quote(boostSh))
            }
            return
        }
        val lightCaps = gameLight &&
            (mode == ModeSwitcher.FAST || mode == ModeSwitcher.LIGHT || mode == ModeSwitcher.BALANCE)
        val env = optionsEnvironment(globalPrefs, appPrefs, game, mode, lightCaps)
        if (optionsSh.isNotEmpty() && File(optionsSh).exists()) {
            shell(env + "sh " + quote(optionsSh))
        }
        if (boostSh.isNotEmpty() && File(boostSh).exists()) {
            shell(env + "sh " + quote(boostSh))
        }
    }

    /** Mode recorded while the battery saver override was enforced. */
    private var saverBackupMode = ""

    /**
     * Keep the device on powersave while the system battery saver is on,
     * mirroring [BatterySaverFollow]: an active game beats the saver, the
     * previous mode is stored once and only restored while still on
     * powersave. Stands down while the accessibility service owns the state.
     */
    private fun followBatterySaver(
        foreground: String,
        games: Set<String>,
        globalPrefs: String,
        appPrefs: String,
        powercfgSh: String,
        optionsSh: String,
        boostSh: String
    ) {
        if (powercfgSh.isEmpty() || !File(powercfgSh).exists()) {
            return
        }
        if (shell("getprop $PROP_ACCESSIBILITY").trim() == "1") {
            return
        }
        if (games.contains(foreground)) {
            return
        }
        val lowPower = shell("settings get global low_power").trim() == "1"
        val current = shell("getprop vtools.powercfg").trim()
        if (lowPower) {
            if (current.isEmpty() || current == ModeSwitcher.POWERSAVE) {
                return
            }
            if (saverBackupMode.isEmpty()) {
                saverBackupMode = current
            }
            switchTo(globalPrefs, appPrefs, powercfgSh, optionsSh, boostSh, ModeSwitcher.POWERSAVE)
        } else if (saverBackupMode.isNotEmpty()) {
            val restore = saverBackupMode
            saverBackupMode = ""
            if (current == ModeSwitcher.POWERSAVE) {
                switchTo(globalPrefs, appPrefs, powercfgSh, optionsSh, boostSh, restore)
            }
        }
    }

    /** Apply a mode through the monitor wrapper plus the option scripts. */
    private fun switchTo(
        globalPrefs: String,
        appPrefs: String,
        powercfgSh: String,
        optionsSh: String,
        boostSh: String,
        mode: String
    ) {
        shell("sh " + quote(powercfgSh) + " " + quote(mode))
        val env = optionsEnvironment(globalPrefs, appPrefs, "", mode, false)
        if (optionsSh.isNotEmpty() && File(optionsSh).exists()) {
            shell(env + "sh " + quote(optionsSh))
        }
        if (boostSh.isNotEmpty() && File(boostSh).exists()) {
            shell(env + "sh " + quote(boostSh))
        }
        SceneStatus.write(mode, "", false, ::shell)
    }

    /** Build the applier environment from the app's preference files. */
    private fun optionsEnvironment(
        globalPrefs: String,
        appPrefs: String,
        packageName: String,
        mode: String,
        lightCaps: Boolean
    ): String {
        val global = readPrefs(globalPrefs)
        val app = readPrefs(appPrefs)

        fun int(key: String, fallback: Int): Int = global[key]?.toIntOrNull() ?: fallback
        fun boolean(key: String, fallback: Boolean): Boolean = global[key]?.toBoolean() ?: fallback
        fun overrideInt(key: String): Int? = app["$packageName.$key"]?.toIntOrNull()
        fun overrideBool(key: String): Boolean? = app["$packageName.$key"]?.toBoolean()

        // The master switch gates the options layer in both control paths:
        // with it off the script undoes whatever the layer had applied, the
        // same reset the app path runs through ProfileOptions.resetScripts.
        if (!boolean("profile_options_enabled", true)) {
            return "export SCENE_QCOM_BUS=0\nexport SCENE_QCOM_GPU=0\n" +
                "export SCENE_QCOM_GPU_PS=0\nexport SCENE_RESET=1\n"
        }

        fun mergeLimits(base: Int, tighter: Int): Int = when {
            tighter <= 0 -> base
            base <= 0 -> tighter
            else -> minOf(base, tighter)
        }

        val limit = mergeLimits(int("profile_limit_percent", 0), if (lightCaps) int("profile_light_cpu_limit", 70) else 0)
        val gpuLimit =
            mergeLimits(int("profile_gpu_limit_percent", 0), if (lightCaps) int("profile_light_gpu_limit", 60) else 0)
        val lite = overrideBool("lite") ?: boolean("profile_lite_mode", false)
        val governor = global["profile_governor"] ?: ""
        val ioSched = global["profile_io_scheduler"] ?: ""
        val pid = boolean("profile_pid_priority", true)
        val extra = boolean("profile_extra_tweaks", false)
        val downscale = overrideInt("downscale")?.let { if (it < 0) int("profile_game_downscale", 0) else it }
            ?: int("profile_game_downscale", 0)
        val fps = overrideInt("fps")?.let { if (it < 0) int("profile_game_fps", 0) else it }
            ?: int("profile_game_fps", 0)
        val dropCaches = boolean("profile_drop_caches_on_game", false)
        val ddrFloor = boolean("profile_game_ddr_floor", true)
        val govTunes = boolean("profile_gov_tunes", false)
        val stopTrace = boolean("profile_stop_trace", false)
        val stopLoggers = boolean("profile_stop_loggers", false)

        val env = StringBuilder()
        env.append("export SCENE_MODE=").append(quote(mode)).append("\n")
        env.append("export SCENE_LIMIT_PERCENT=").append(quote(limit.toString())).append("\n")
        env.append("export SCENE_GPU_LIMIT=").append(quote(gpuLimit.toString())).append("\n")
        env.append("export SCENE_LITE=").append(quote(if (lite) "1" else "0")).append("\n")
        env.append("export SCENE_GOVERNOR=").append(quote(governor)).append("\n")
        env.append("export SCENE_IOSCHED=").append(quote(ioSched)).append("\n")
        env.append("export SCENE_PID=").append(quote(if (pid) "1" else "0")).append("\n")
        env.append("export SCENE_GAME_PKG=").append(quote(packageName)).append("\n")
        env.append("export SCENE_EXTRA_TWEAKS=").append(quote(if (extra) "1" else "0")).append("\n")
        env.append("export SCENE_GAME_DOWNSCALE=").append(quote(downscale.toString())).append("\n")
        env.append("export SCENE_GAME_FPS=").append(quote(fps.toString())).append("\n")
        env.append("export SCENE_DROP_CACHES=").append(quote(if (dropCaches) "1" else "0")).append("\n")
        env.append("export SCENE_GAME_DDR_FLOOR=")
            .append(quote(if (packageName.isNotEmpty() && ddrFloor && !lightCaps) "1" else "0")).append("\n")
        env.append("export SCENE_GOV_TUNES=").append(quote(if (govTunes) "1" else "0")).append("\n")
        env.append("export SCENE_STOP_TRACE=").append(quote(if (stopTrace) "1" else "0")).append("\n")
        env.append("export SCENE_STOP_LOGGERS=").append(quote(if (stopLoggers) "1" else "0")).append("\n")
        val qcomBus = boolean("profile_qcom_bus_boost", false)
        val qcomGpu = boolean("profile_qcom_gpu_boost", false)
        val qcomGpuPs = boolean("profile_qcom_gpu_powersave", false)
        env.append("export SCENE_QCOM_BUS=").append(quote(if (qcomBus) "1" else "0")).append("\n")
        env.append("export SCENE_QCOM_GPU=").append(quote(if (qcomGpu) "1" else "0")).append("\n")
        env.append("export SCENE_QCOM_GPU_PS=").append(quote(if (qcomGpuPs) "1" else "0")).append("\n")
        env.append("export SCENE_SDK=").append(android.os.Build.VERSION.SDK_INT).append("\n")
        return env.toString()
    }

    private fun readPrefs(path: String): Map<String, String> {
        if (path.isEmpty()) {
            return emptyMap()
        }
        return try {
            val file = File(path)
            if (!file.isFile) {
                return emptyMap()
            }
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = doc.getElementsByTagName("*")
            val map = HashMap<String, String>()
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                val name = node.attributes?.getNamedItem("name")?.nodeValue ?: continue
                val value = node.attributes.getNamedItem("value")?.nodeValue ?: node.textContent
                if (value != null) {
                    map[name] = value
                }
            }
            map
        } catch (ex: Exception) {
            emptyMap()
        }
    }

    private fun readProfiles(path: String): Map<String, String> {
        return GameProfileStore.parseProfiles(readText(path))
    }

    private fun readText(path: String): String {
        return try {
            val file = File(path)
            if (!file.isFile) "" else file.readText()
        } catch (ex: Exception) {
            ""
        }
    }

    private fun writeFile(path: String, body: String) {
        shell(
            "mkdir -p /data/adb/scene\n" +
                "cat > " + quote(path) + " << 'SCENE_GAME_PROFILE_EOF'\n" +
                body + "\nSCENE_GAME_PROFILE_EOF"
        )
    }

    /** GPU busy percentage from the first kgsl node the kernel exposes. */
    private fun gpuBusyPercent(): Double? {
        for (node in GPU_BUSY_NODES.split(" ")) {
            val raw = shell("cat " + quote(node) + " 2> /dev/null").trim()
            if (raw.isEmpty()) {
                continue
            }
            val value = raw.replace("%", "").split(Regex("\\s+")).firstOrNull()?.toDoubleOrNull()
            if (value != null && value >= 0.0 && value <= 100.0) {
                return value
            }
        }
        return null
    }

    /** Display frame rate from the sde crtc node (same source as FpsUtils). */
    private fun measuredFps(): Double? {
        val raw = shell("cat " + quote(FPS_NODE) + " 2> /dev/null").trim()
        if (raw.isEmpty()) {
            return null
        }
        val firstLine = raw.lineSequence().firstOrNull() ?: return null
        val parts = firstLine.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val candidates = listOfNotNull(parts.getOrNull(1), parts.getOrNull(0))
        for (candidate in candidates) {
            val value = candidate.toDoubleOrNull()
            if (value != null && value > 1.0 && value <= 240.0) {
                return value
            }
        }
        return null
    }

    private fun foregroundPackage(): String {
        val out = shell(
            "dumpsys activity activities 2> /dev/null | grep -m1 -E 'topResumedActivity|mResumedActivity'"
        )
        return Regex("([a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+)/").find(out)?.groupValues?.get(1) ?: ""
    }

    private fun readLines(path: String): Set<String> {
        return try {
            val file = File(path)
            if (!file.isFile) {
                emptySet()
            } else {
                file.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
            }
        } catch (ex: Exception) {
            emptySet()
        }
    }

    private fun writeStatus(path: String, foreground: String) {
        try {
            val file = File(path)
            file.parentFile?.mkdirs()
            val tmp = File(path + ".tmp")
            tmp.writeText("focused_app $foreground\nscreen_awake 1\n")
            tmp.renameTo(file)
        } catch (ex: Exception) {
            // Status is diagnostic only.
        }
    }

    private fun shell(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output
        } catch (ex: Exception) {
            ""
        }
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
