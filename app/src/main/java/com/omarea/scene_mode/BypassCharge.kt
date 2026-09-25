package com.omarea.scene_mode

import android.content.Context
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.ShellEscape
import com.omarea.store.SpfConfig
import com.omarea.utils.SceneLog
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bypass charging: stops the battery from charging while plugged in, so a
 * gaming session runs directly off the charger instead of cycling the battery
 * (less heat, less wear).
 *
 * The feature has several callers (game session, charge protection level, QS
 * tile), so the node is owned here and each caller holds a *reason*. The node
 * stays bypassed while any reason is set and is released only when the last
 * one clears, which keeps the game path, the charge-protection path and the
 * manual toggle from fighting each other.
 *
 * Node table and lazy probe concept adapted from AZenith (Apache-2.0), trimmed
 * to the Qualcomm / Xiaomi node set. The working node is verified by watching
 * the charge current drop, so an unsupported node is never left enabled.
 */
object BypassCharge {
    /** Game session asked for bypass (profile options). */
    const val REASON_GAME = "game"

    /** User asked for bypass (charge screen / tile). */
    const val REASON_MANUAL = "manual"

    /** Charge protection level reached (BatteryReceiver). */
    const val REASON_PROTECT = "protect"

    private val REASONS = listOf(REASON_GAME, REASON_MANUAL, REASON_PROTECT)

    private data class Node(val name: String, val path: String, val on: String, val off: String)

    /**
     * Qualcomm / Xiaomi candidates, most common first. The extended entries
     * are merged from AZenith's node table (Apache-2.0), trimmed to the
     * Qualcomm / Xiaomi / common power_supply nodes; the current-drop probe
     * validates whichever node is actually present.
     */
    private val candidates = listOf(
        Node("battery_input_suspend", "/sys/class/power_supply/battery/input_suspend", "1", "0"),
        Node("qcom_input_suspend", "/sys/class/qcom-battery/input_suspend", "1", "0"),
        Node("battery_charging_enabled", "/sys/class/power_supply/battery/battery_charging_enabled", "0", "1"),
        Node("battery_charging_enabled2", "/sys/class/power_supply/battery/charging_enabled", "0", "1"),
        Node("battery_charge_disable", "/sys/class/power_supply/battery/charge_disable", "1", "0"),
        Node("qpnp_blocking", "/sys/class/power_supply/qpnp_adaptive_charge/blocking", "1", "0"),
        Node("qpnp_input_suspend", "/sys/class/power_supply/qpnp_adaptive_charge/input_suspend", "1", "0"),
        Node("mca_input_suspend", "/sys/class/power_supply/mca_charge_interface/input_suspend", "1", "0"),
        Node("qcom_restricted_charging", "/sys/class/qcom-battery/restricted_charging", "1", "0"),
        Node("constant_charge_current_max", "/sys/class/power_supply/battery/constant_charge_current_max", "0", "3000000"),
        // AZenith merge: common + Qualcomm + Xiaomi specific paths.
        Node("battery_charge_enabled", "/sys/class/power_supply/battery/charge_enabled", "0", "1"),
        Node("battery_charger_control", "/sys/class/power_supply/battery/charger_control", "0", "1"),
        Node("battery_device_charging_enable", "/sys/class/power_supply/battery/device/Charging_Enable", "0", "1"),
        Node("ac_charging_enabled", "/sys/class/power_supply/ac/charging_enabled", "0", "1"),
        Node("dc_charging_enabled", "/sys/class/power_supply/dc/charging_enabled", "0", "1"),
        Node("qcom_charging_enabled", "/sys/class/qcom-battery/charging_enabled", "0", "1"),
        Node("qcom_cool_mode", "/sys/class/qcom-battery/cool_mode", "1", "0"),
        Node("qcom_batt_protect_en", "/sys/class/qcom-battery/batt_protect_en", "1", "0"),
        Node("qcom_battery_protected", "/sys/class/qcom-battery/battery_protected", "1", "0"),
        Node(
            "pmic_glink_force_suspend",
            "/sys/devices/platform/soc/soc:qcom,pmic_glink/soc:qcom,pmic_glink:qcom,battery_charger/force_charger_suspend",
            "1",
            "0"
        ),
        Node("qpnp_adaptive_blocking", "/sys/module/qpnp_adaptive_charge/parameters/blocking", "1", "0"),
        Node("mca_input_suspend_soc", "/sys/devices/platform/soc/soc:mca_charge_interface/input_suspend", "1", "0"),
        Node("mca_charge_enable", "/sys/devices/platform/soc/soc:mca_charge_interface/charge_enable", "0", "1"),
        Node("mca_stop_handle", "/sys/devices/platform/soc/soc:mca_business_charger/stop_handle_charge", "1", "0"),
        Node("xm_input_suspend", "/sys/class/xm_power/charger/charge_interface/input_suspend", "1", "0"),
        Node("xm_charge_enable", "/sys/class/xm_power/charger/charge_interface/charge_enable", "0", "1"),
        Node("xm_stop_handle", "/sys/class/xm_power/charger/charger_common/stop_handle_charge", "1", "0")
    )

    private const val PROP_NODE = "vtools.scene.bypass.node"
    private const val PROP_ACTIVE = "vtools.scene.bypass.active"
    private const val PROP_AUTO = "vtools.scene.bypass.auto"

    private fun reasonProp(reason: String) = "vtools.scene.bypass.reason.$reason"

    private fun shell(command: String): String = KeepShellPublic.doCmdSync(command).trim()

    private fun exists(path: String): Boolean = shell("test -e " + ShellEscape.quote(path) + " && echo 1") == "1"

    private fun getProp(name: String): String = shell("getprop " + ShellEscape.quote(name))

    private fun setProp(name: String, value: String) {
        shell("setprop " + ShellEscape.quote(name) + " " + ShellEscape.quote(value))
    }

    private fun readInt(path: String, fallback: Int): Int =
        shell("cat " + ShellEscape.quote(path)).toIntOrNull() ?: fallback

    private fun readString(path: String): String = shell("cat " + ShellEscape.quote(path))

    private fun writeNode(path: String, value: String) {
        shell(
            "chmod 0664 " + ShellEscape.quote(path) + " 2> /dev/null\n" +
                "echo " + ShellEscape.quote(value) + " > " + ShellEscape.quote(path) + " 2> /dev/null"
        )
    }

    /** The node that should be used: the remembered one when it still exists. */
    private fun findCandidate(): Node? {
        val remembered = getProp(PROP_NODE)
        if (remembered.isNotEmpty()) {
            candidates.firstOrNull { it.name == remembered }?.let { hit ->
                if (exists(hit.path)) {
                    return hit
                }
            }
        }
        return candidates.firstOrNull { exists(it.path) }
    }

    fun supported(): Boolean = findCandidate() != null

    fun isActive(): Boolean = getProp(PROP_ACTIVE) == "1"

    /** True when the game-session path engaged bypass. */
    fun isAuto(): Boolean = isReasonSet(REASON_GAME)

    /** True when the charge-protection level engaged bypass. */
    fun isProtecting(): Boolean = isReasonSet(REASON_PROTECT)

    /** True when the user engaged bypass from the charge screen or the tile. */
    fun isManual(): Boolean = isReasonSet(REASON_MANUAL)

    private fun isReasonSet(reason: String): Boolean = getProp(reasonProp(reason)) == "1"

    private fun setReasonProp(reason: String, on: Boolean) {
        setProp(reasonProp(reason), if (on) "1" else "")
    }

    private fun anyReasonSet(): Boolean = REASONS.any { isReasonSet(it) }

    /**
     * Add or clear one caller's reason. The node is enabled with the first
     * reason and released only when the last reason clears, so overlapping
     * callers (game + protection + manual) never fight.
     */
    fun setReason(reason: String, on: Boolean) {
        if (on) {
            setReasonProp(reason, true)
            if (!isActive()) {
                enableNode(auto = reason == REASON_GAME)
            }
        } else {
            setReasonProp(reason, false)
            if (isActive() && !anyReasonSet()) {
                disableNode()
            }
        }
    }

    /** Name of the remembered node, or null when none has been detected yet. */
    fun currentNodeName(): String? = getProp(PROP_NODE).takeIf { it.isNotEmpty() }

    /** Enable bypass when a usable node exists, the charger is connected and the level is safe. */
    fun enableIfNeeded(context: Context, auto: Boolean = false) {
        val spf = context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
        val threshold = spf.getInt(
            SpfConfig.GLOBAL_SPF_BYPASS_THRESHOLD,
            SpfConfig.GLOBAL_SPF_BYPASS_THRESHOLD_DEFAULT
        )
        val capacity = readInt("/sys/class/power_supply/battery/capacity", -1)
        if (capacity < threshold) {
            return
        }
        val status = readString("/sys/class/power_supply/battery/status")
        if (!status.equals("Charging", ignoreCase = true)) {
            return
        }
        setReason(if (auto) REASON_GAME else REASON_MANUAL, true)
    }

    fun enable(auto: Boolean = false) {
        setReason(if (auto) REASON_GAME else REASON_MANUAL, true)
    }

    /** Force bypass off: clears every reason (QS tile long path / master off). */
    fun disable() {
        REASONS.forEach { setReasonProp(it, false) }
        if (isActive()) {
            disableNode()
        }
    }

    private fun enableNode(auto: Boolean) {
        val node = findCandidate() ?: return
        setProp(PROP_NODE, node.name)
        setProp(PROP_AUTO, if (auto) "1" else "0")
        writeNode(node.path, node.on)
        setProp(PROP_ACTIVE, "1")
        setProp("vtools.bp", "1")
        SceneLog.i("BypassCharge", "bypass enabled via ${node.name} (auto=$auto)")
    }

    private fun disableNode() {
        val remembered = getProp(PROP_NODE)
        val node = candidates.firstOrNull { it.name == remembered }
        if (node != null && exists(node.path)) {
            writeNode(node.path, node.off)
        } else {
            // No usable record: put every existing candidate back to its safe value.
            candidates.forEach { candidate ->
                if (exists(candidate.path)) {
                    writeNode(candidate.path, candidate.off)
                }
            }
        }
        setProp(PROP_ACTIVE, "0")
        setProp(PROP_AUTO, "0")
        setProp("vtools.bp", "0")
        SceneLog.i("BypassCharge", "bypass disabled")
    }

    /** Re-assert the active node, in case a vendor daemon reset it. */
    fun reassert() {
        if (!isActive()) {
            return
        }
        val node = candidates.firstOrNull { it.name == getProp(PROP_NODE) } ?: return
        if (exists(node.path)) {
            writeNode(node.path, node.on)
        }
    }

    /**
     * Probe candidates until the charge current drops, then remember the node.
     * Runs off the main thread; used by the "Detect" action in the charge screen.
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun detect(context: Context, onResult: (String?) -> Unit) {
        GlobalScope.launch(Dispatchers.IO) {
            val before = currentMa()
            for (candidate in candidates) {
                if (!exists(candidate.path)) {
                    continue
                }
                writeNode(candidate.path, candidate.on)
                delay(2500)
                val after = currentMa()
                if (after in 0..49 || (before > 200 && after < before / 2)) {
                    setProp(PROP_NODE, candidate.name)
                    // Leave the node off until bypass is actually requested.
                    writeNode(candidate.path, candidate.off)
                    withContext(Dispatchers.Main) { onResult(candidate.name) }
                    return@launch
                }
                writeNode(candidate.path, candidate.off)
                delay(500)
            }
            withContext(Dispatchers.Main) { onResult(null) }
        }
    }

    private fun currentMa(): Int {
        val raw = readInt("/sys/class/power_supply/battery/current_now", -1)
        if (raw < 0) {
            return raw
        }
        // current_now is microamps on most Qualcomm kernels and milliamps on a few.
        return if (raw > 5000) raw / 1000 else raw
    }
}
