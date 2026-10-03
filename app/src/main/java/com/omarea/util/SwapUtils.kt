package com.omarea.util

import android.content.Context
import com.omarea.common.shared.FileWrite
import com.omarea.common.shell.KeepShellPublic

/**
 * Manual RAM reclaim on top of kswapd (levels 0..3).
 *
 * Responsibility: run the bundled force_compact.sh and report its output.
 * Non-goals: swap/zRAM configuration — the kernel/ROM owns that now;
 * Scene's swap manager (screen, boot re-apply, prefs, shell assets) was
 * removed (AGENTS.md hard rule 6).
 */
class SwapUtils(private val context: Context) {
    private var swapForceKswapdScript: String? = null

    /**
     * level: 0 minimal · 1 light · 2 heavier · 3 extreme.
     * Returns the script's output (or "Fail!" when the asset is missing).
     */
    fun forceKswapd(level: Int): String {
        if (swapForceKswapdScript == null) {
            swapForceKswapdScript = FileWrite.writePrivateShellFile("addin/force_compact.sh", "addin/force_compact.sh", context)
            KeepShellPublic.doCmdSync("rm /cache/force_compact.log 2>/dev/null")
        }

        if (swapForceKswapdScript != null) {
            return KeepShellPublic.getInstance("swap-clear", true).doCmdSync("sh $swapForceKswapdScript $level")
        }
        return "Fail!"
    }
}
