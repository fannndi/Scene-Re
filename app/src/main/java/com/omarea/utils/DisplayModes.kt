package com.omarea.utils

import android.content.Context
import com.omarea.common.shared.FileWrite
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.ShellEscape

/**
 * Refresh-rate (display mode) control.
 *
 * The mode table comes from the kr-script `display_modes.sh` probe (it knows the
 * Android 11 vs 12+ dumpsys layouts), the active mode from `dumpsys display`
 * and the switch goes through `SurfaceFlinger 1035` like the floating selector.
 *
 * Used by the floating selector and by the per-game refresh-rate override; every
 * call is a shell round-trip, so callers run them off the main thread when they
 * can.
 */
object DisplayModes {
    data class Mode(val id: Int, val label: String) {
        /** "120Hz" -> 120 (0 when the label has no number). */
        val hz: Int
            get() = Regex("([0-9]+)").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /** Installed display modes, highest refresh rate first. */
    fun list(context: Context): List<Mode> {
        val script = try {
            FileWrite.writePrivateShellFile(
                "kr-script/display/display_modes.sh",
                "display_modes.sh",
                context
            )
        } catch (ex: Exception) {
            null
        } ?: return emptyList()
        val output = KeepShellPublic.doCmdSync("sh " + ShellEscape.quote(script) + " 2> /dev/null")
        return parseModes(output)
    }

    /** `<id>|<label>` lines from display_modes.sh -> modes, highest Hz first. */
    fun parseModes(output: String): List<Mode> =
        output.lineSequence().mapNotNull { line ->
            val parts = line.split("|", limit = 2)
            if (parts.size != 2) {
                return@mapNotNull null
            }
            val id = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
            val label = parts[1].trim()
            if (label.isEmpty()) null else Mode(id, label)
        }.sortedWith(compareByDescending<Mode> { it.hz }.thenBy { it.id }).toList()

    /** Id of the active SurfaceFlinger display mode, or null. */
    fun active(context: Context): Int? {
        val output = KeepShellPublic.doCmdSync("dumpsys display")
        return Regex("mActiveSfDisplayMode=DisplayMode\\{id=([0-9]+)")
            .find(output)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    /** Switch the display mode; SurfaceFlinger reads it back as the active one. */
    fun set(context: Context, id: Int) {
        if (id < 0) {
            return
        }
        KeepShellPublic.doCmdSync("service call SurfaceFlinger 1035 i32 $id")
    }

    fun label(modes: List<Mode>, id: Int): String = modes.find { it.id == id }?.label ?: "$id"
}
