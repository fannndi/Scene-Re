package com.omarea.scene_mode

import android.content.Context
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.RootFile
import com.omarea.library.shell.PlatformUtils

/**
 * External configuration support (/data/powercfg.sh).
 *
 * The bundled shell profiles were replaced by the device tuning JSON
 * (see com.omarea.core.control.ProfileController); this class only detects,
 * validates and removes the external power-user escape hatch.
 *
 * Responsibility: external script detection/validation + platform support.
 * Non-goals: applying modes (ModeSwitcher), shipping bundled scripts.
 */
class CpuConfigInstaller {

    private val rootDir = "powercfg"

    fun removeOutsideConfig() {
        KeepShellPublic.doCmdSync("rm -f ${ModeSwitcher.OUTSIDE_POWER_CFG_PATH}")
        KeepShellPublic.doCmdSync("rm -f ${ModeSwitcher.OUTSIDE_POWER_CFG_BASE}")
    }

    /** Normalizes line endings and permissions of the external scripts. */
    fun configCodeVerify() {
        try {
            KeepShellPublic.doCmdSync(
                "if [[ -f ${ModeSwitcher.OUTSIDE_POWER_CFG_PATH} ]]; then " +
                    "busybox sed -i 's/\\r//' ${ModeSwitcher.OUTSIDE_POWER_CFG_PATH}; " +
                    "chmod 0775 ${ModeSwitcher.OUTSIDE_POWER_CFG_PATH}; fi"
            )
            KeepShellPublic.doCmdSync(
                "if [[ -f ${ModeSwitcher.OUTSIDE_POWER_CFG_BASE} ]]; then " +
                    "busybox sed -i 's/\\r//' ${ModeSwitcher.OUTSIDE_POWER_CFG_BASE}; " +
                    "chmod 0777 ${ModeSwitcher.OUTSIDE_POWER_CFG_BASE}; fi"
            )
        } catch (_: Exception) {
        }
    }

    /** True when a bundled tuning exists for the current platform. */
    fun dynamicSupport(context: Context): Boolean {
        val cpuName = PlatformUtils().getCPUName()
        return context.assets.list(rootDir)?.contains(cpuName) == true
    }

    fun outsideConfigInstalled(): Boolean =
        RootFile.fileNotEmpty(ModeSwitcher.OUTSIDE_POWER_CFG_PATH)
}
