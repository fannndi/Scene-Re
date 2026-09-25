package com.omarea.scene_mode

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Foreground monitor companion, meant to be started with `app_process` as root:
 *
 *   app_process -Djava.class.path=<apk> / --nice-name=sys.scene-monitor \
 *       com.omarea.scene_mode.SystemMonitor <status> <games> <globalPrefs> \
 *       <appPrefs> <switchSh> <optionsSh> <boostSh> <gameMode> [intervalMs]
 *
 * It exists so the automatic game profile still works when the accessibility
 * service is unavailable (HyperOS and some ROMs kill it aggressively). Unlike
 * AZenith's monitor it does not use any hidden-API bypass: the foreground
 * package comes from a `dumpsys` probe, everything else from sysfs.
 *
 * While the accessibility service is running (`vtools.scene.accessibility=1`)
 * the monitor only records the status; the app owns the switching.
 */
object SystemMonitor {
    private const val DEFAULT_INTERVAL_MS = 3000L
    private const val PROP_ACCESSIBILITY = "vtools.scene.accessibility"

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
        val interval = args.getOrNull(8)?.toLongOrNull() ?: DEFAULT_INTERVAL_MS

        var lastPackage = ""
        var previousMode = ""
        var games = readLines(gamesFile)

        while (true) {
            try {
                val foreground = foregroundPackage()
                if (foreground != lastPackage) {
                    lastPackage = foreground
                    games = readLines(gamesFile)
                    if (foreground.isNotEmpty() && shell("getprop $PROP_ACCESSIBILITY").trim() != "1") {
                        if (games.contains(foreground)) {
                            applyGameMode(foreground, globalPrefs, appPrefs, powercfgSh, optionsSh, boostSh, gameMode)?.let {
                                previousMode = it
                            }
                        } else if (previousMode.isNotEmpty()) {
                            restoreMode(previousMode, powercfgSh, optionsSh, boostSh)
                            previousMode = ""
                        }
                    }
                    writeStatus(statusFile, foreground)
                }
            } catch (ex: Exception) {
                // Never let a probe failure kill the loop.
            }
            Thread.sleep(interval)
        }
    }

    private fun applyGameMode(
        packageName: String,
        globalPrefs: String,
        appPrefs: String,
        powercfgSh: String,
        optionsSh: String,
        boostSh: String,
        gameMode: String
    ): String? {
        if (powercfgSh.isEmpty() || !File(powercfgSh).exists()) {
            return null
        }
        val current = shell("getprop vtools.powercfg").trim()
        val previous = if (current.isNotEmpty() && current != gameMode) current else ""
        shell("sh " + quote(powercfgSh) + " " + quote(gameMode) + " " + quote(packageName))

        val disabled = readPrefs(appPrefs)["$packageName.enabled"]?.toIntOrNull() == 0
        if (disabled) {
            if (optionsSh.isNotEmpty() && File(optionsSh).exists()) {
                shell(
                    "export SCENE_QCOM_BUS=0; export SCENE_QCOM_GPU=0; export SCENE_QCOM_GPU_PS=0;" +
                        " export SCENE_RESET=1; sh " + quote(optionsSh)
                )
            }
            releaseBoost(boostSh)
            SceneStatus.write(gameMode, packageName, true, ::shell)
        } else {
            val env = optionsEnvironment(globalPrefs, appPrefs, packageName, gameMode)
            if (optionsSh.isNotEmpty() && File(optionsSh).exists()) {
                shell(env + "sh " + quote(optionsSh))
            }
            if (boostSh.isNotEmpty() && File(boostSh).exists()) {
                shell(env + "sh " + quote(boostSh))
            }
            SceneStatus.write(gameMode, packageName, true, ::shell)
        }
        return previous
    }

    private fun restoreMode(mode: String, powercfgSh: String, optionsSh: String, boostSh: String) {
        if (powercfgSh.isNotEmpty()) {
            shell("sh " + quote(powercfgSh) + " " + quote(mode))
        }
        if (optionsSh.isNotEmpty()) {
            shell("SCENE_MODE=" + quote(mode) + " SCENE_GAME_RESET=1 sh " + quote(optionsSh))
        }
        releaseBoost(boostSh)
        SceneStatus.write(mode, "", false, ::shell)
    }

    private fun releaseBoost(boostSh: String) {
        if (boostSh.isNotEmpty() && File(boostSh).exists()) {
            shell("SCENE_QCOM_BUS=0 SCENE_QCOM_GPU=0 SCENE_QCOM_GPU_PS=0 sh " + quote(boostSh))
        }
    }

    /** Build the applier environment from the app's preference files. */
    private fun optionsEnvironment(
        globalPrefs: String,
        appPrefs: String,
        packageName: String,
        mode: String
    ): String {
        val global = readPrefs(globalPrefs)
        val app = readPrefs(appPrefs)

        fun int(key: String, fallback: Int): Int = global[key]?.toIntOrNull() ?: fallback
        fun boolean(key: String, fallback: Boolean): Boolean = global[key]?.toBoolean() ?: fallback
        fun overrideInt(key: String): Int? = app["$packageName.$key"]?.toIntOrNull()
        fun overrideBool(key: String): Boolean? = app["$packageName.$key"]?.toBoolean()

        val limit = int("profile_limit_percent", 0)
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
        val govTunes = boolean("profile_gov_tunes", false)
        val stopTrace = boolean("profile_stop_trace", false)
        val stopLoggers = boolean("profile_stop_loggers", false)

        val env = StringBuilder()
        env.append("export SCENE_MODE=").append(quote(mode)).append("\n")
        env.append("export SCENE_LIMIT_PERCENT=").append(quote(limit.toString())).append("\n")
        env.append("export SCENE_LITE=").append(quote(if (lite) "1" else "0")).append("\n")
        env.append("export SCENE_GOVERNOR=").append(quote(governor)).append("\n")
        env.append("export SCENE_IOSCHED=").append(quote(ioSched)).append("\n")
        env.append("export SCENE_PID=").append(quote(if (pid) "1" else "0")).append("\n")
        env.append("export SCENE_GAME_PKG=").append(quote(packageName)).append("\n")
        env.append("export SCENE_EXTRA_TWEAKS=").append(quote(if (extra) "1" else "0")).append("\n")
        env.append("export SCENE_GAME_DOWNSCALE=").append(quote(downscale.toString())).append("\n")
        env.append("export SCENE_GAME_FPS=").append(quote(fps.toString())).append("\n")
        env.append("export SCENE_DROP_CACHES=").append(quote(if (dropCaches) "1" else "0")).append("\n")
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
