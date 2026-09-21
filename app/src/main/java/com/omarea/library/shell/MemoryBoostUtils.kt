package com.omarea.library.shell

import android.content.Context
import com.omarea.common.shared.FileWrite
import com.omarea.common.shell.KeepShellPublic

/**
 * Memory reclaim helper shared by the Home screen and dynamic scene mode.
 * (Extracted from the removed swap controller feature.)
 */
class MemoryBoostUtils(private val context: Context) {
    private var forceCompactScript: String? = null

    /**
     * Force kernel memory reclaim.
     * level: 0 = very light, 1 = light, 2 = heavier, 3 = extreme
     */
    fun forceKswapd(level: Int): String {
        if (forceCompactScript == null) {
            forceCompactScript = FileWrite.writePrivateShellFile("addin/force_compact.sh", "addin/force_compact.sh", context)
            KeepShellPublic.doCmdSync("rm /cache/force_compact.log 2>/dev/null")
        }

        if (forceCompactScript != null) {
            return KeepShellPublic.getInstance("memory-clear", true).doCmdSync("sh $forceCompactScript $level")
        }
        return "Fail!"
    }
}
