package com.omarea.utils

import com.omarea.common.shell.KeepShellPublic

/**
 * MIUI's own thermal state and control surface on Xiaomi kernels.
 *
 * The stock MIUI 14 surya kernel exposes the Xiaomi `thermal_message` driver
 * (`sconfig`, `temp_state`, `board_sensor(_temp)`, `cpu_limits`, `boost`,
 * `screen_state`) and runs `mi_thermald`, which selects one of the encrypted
 * `/vendor/etc/thermal-*.conf` files through
 * `/data/vendor/thermal/thermal-global-mode` (the key into
 * `/vendor/etc/thermal-map.conf`).
 *
 * Verified map keys: 0 normal, 8 phone, 9/13/16 tgame (games), 10 nolimits,
 * 12 camera, 15 arvr. The runtime config directory
 * `/data/vendor/thermal/config/` wins over `/vendor/etc/` and is what MIUI and
 * Game Turbo use for live overrides.
 *
 * Everything here is read-only except [modeTargets] bookkeeping; the actual
 * switching is done by the profile-option scripts as root.
 */
object MiuThermal {
    const val MODE_FILE = "/data/vendor/thermal/thermal-global-mode"
    const val TEMP_STATE_NODE = "/sys/class/thermal/thermal_message/temp_state"
    const val SCONFIG_NODE = "/sys/class/thermal/thermal_message/sconfig"
    const val RUNTIME_CONFIG_DIR = "/data/vendor/thermal/config"
    const val DUMP_FILE = "/data/vendor/thermal/thermal.dump"
    const val ENGINE_PROP = "persist.sys.thermal.config"
    private const val ENGINE_MAP = "/vendor/etc/thermal-engine-map.conf"

    /** Modes the user may force, in the order the UI lists them. */
    val CHOICES = listOf(0, 9, 10, 8)

    /** `thermal-map.conf` key -> the config file MIUI ships for it. */
    fun configName(mode: Int): String = when (mode) {
        0 -> "thermal-normal.conf"
        8 -> "thermal-phone.conf"
        9, 13, 16 -> "thermal-tgame.conf"
        10 -> "thermal-nolimits.conf"
        12 -> "thermal-camera.conf"
        15 -> "thermal-arvr.conf"
        else -> ""
    }

    /**
     * The choices whose config file actually exists in `/vendor/etc`, so the
     * script can never point the daemon at a config that is not shipped.
     */
    fun availableModes(): List<Int> {
        val cmd = StringBuilder()
        for (mode in CHOICES) {
            val name = configName(mode)
            if (name.isEmpty()) {
                continue
            }
            cmd.append("if [ -e /vendor/etc/").append(name).append(" ]; then echo ")
                .append(mode).append("; fi\n")
        }
        val found = try {
            shell(cmd.toString()).lines().mapNotNull { it.trim().toIntOrNull() }.toSet()
        } catch (ex: Exception) {
            emptySet()
        }
        return CHOICES.filter { it in found }
    }

    /** Xiaomi thermal state (0 cool .. 5 hot), null when the node is absent. */
    fun tempState(): Int? = try {
        shell("cat $TEMP_STATE_NODE 2> /dev/null").trim().toIntOrNull()
    } catch (ex: Exception) {
        null
    }

    /** The mode mi_thermald is currently running, null when unreadable. */
    fun globalMode(): Int? = try {
        shell("cat $MODE_FILE 2> /dev/null").trim().toIntOrNull()
    } catch (ex: Exception) {
        null
    }

    /** Config name the QTI engine property points at ("" when unset). */
    fun engineConfig(): String = try {
        shell("getprop $ENGINE_PROP 2> /dev/null").trim()
    } catch (ex: Exception) {
        ""
    }

    /**
     * The QTI `thermal-engine` channel (the library Game Turbo links against).
     * The init service is commented out on stock MIUI 14, so this reports
     * inactive unless the ROM actually ships a running engine or its map.
     */
    fun engineActive(): Boolean = try {
        val service = shell("getprop init.svc.thermal-engine 2> /dev/null").trim()
        service == "running" || shell("test -e $ENGINE_MAP && echo 1 2> /dev/null").trim() == "1"
    } catch (ex: Exception) {
        false
    }

    /** Tolerant parse of a `thermal-engine-map.conf` (or mi_thermald map). */
    fun parseMap(text: String): Map<Int, String> {
        val rows = HashMap<Int, String>()
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue
            }
            val match = Regex("^\\[?\\s*(\\d+)\\s*[:=]\\s*([^\\]\\s]+)\\s*\\]?$").find(trimmed)
                ?: continue
            val mode = match.groupValues[1].toIntOrNull() ?: continue
            rows[mode] = match.groupValues[2]
        }
        return rows
    }

    /** Short label for a thermal-map key, for reports and dialogs. */
    fun modeLabel(mode: Int): String {
        val name = configName(mode)
        return when {
            mode == 0 -> "ROM default (normal)"
            name.isNotEmpty() -> "$mode \u2192 $name"
            else -> mode.toString()
        }
    }

    private fun shell(command: String): String = KeepShellPublic.doCmdSync(command)
}
