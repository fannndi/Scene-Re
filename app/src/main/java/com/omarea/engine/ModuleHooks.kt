package com.omarea.engine


/**
 * Systemless file hooks (ports the old kr-script common/magisk*.sh behaviour).
 *
 * The hook disables a vendor file by placing an empty replacement into the
 * Scene systemless module; APatch/Magisk overlays it at boot.
 *
 * Responsibility: detect module support and install/remove file hooks.
 * Non-goals: creating the module itself (it must already be installed).
 */
object ModuleHooks {

    /** Scene's systemless module path (same name the old scripts expect). */
    const val MODULE_DIR = "/data/adb/modules/scene_systemless"

    // ---------------------------------------------------------------------
    // Hookable perf components. Paths verified against the stock
    // MIUI 14 / Android 12 surya ROM (SDM732G, soc_id 365/366 "moorea",
    // perf target "sdmmagpie"):
    //  - perfboostsconfig.xml: launch/resume/scroll/fling boosts
    //  - perfconfigstore.xml : perf HAL properties (iop/lmk/gesture flags)
    //  - powerhint.xml       : QVR/camera power hints (freq + GPU levels)
    //  - perfservice         : userspace perf helper (system_ext)
    //  - perf HAL service    : the root perf daemon (2.2); this ROM has NO
    //                          /system/vendor/bin/perfd at all — the old
    //                          "perfd" hook target was dead weight.
    // ---------------------------------------------------------------------
    const val PERFBOOSTS = "/system/vendor/etc/perf/perfboostsconfig.xml"
    const val PERFCONFIGSTORE = "/system/vendor/etc/perf/perfconfigstore.xml"
    const val POWERHINT = "/system/vendor/etc/powerhint.xml"
    const val PERFSERVICE = "/system_ext/bin/perfservice"
    const val PERF_HAL = "/system/vendor/bin/hw/vendor.qti.hardware.perf@2.2-service"

    /** Order shown in the Tweaks screen; label doubles as the row title. */
    val targets = listOf(PERFBOOSTS, PERFCONFIGSTORE, POWERHINT, PERFSERVICE, PERF_HAL)

    val labels = mapOf(
        PERFBOOSTS to "Performance boosts",
        PERFCONFIGSTORE to "Perf config store",
        POWERHINT to "Power hints (QVR)",
        PERFSERVICE to "perfservice",
        PERF_HAL to "Perf HAL service (advanced)"
    )

    fun moduleInstalled(): Boolean =
        RootShell.run("[ -d $MODULE_DIR ] && echo 1 || echo 0").trim() == "1"

    /** Support = target (or its backup) exists on the device. */
    fun targetExists(target: String): Boolean =
        RootShell.run(
            "[ -f $target ] || [ -f $target.bak ] && echo 1 || echo 0"
        ).trim() == "1"

    /** True when the module currently overlays an empty file for [target]. */
    fun isHooked(target: String): Boolean =
        RootShell.run("[ -f $MODULE_DIR$target ] && echo 1 || echo 0").trim() == "1"

    /** Overlays an empty file (disable) or removes the overlay (restore). */
    fun setHooked(target: String, hooked: Boolean): Boolean {
        if (!moduleInstalled()) return false
        return if (hooked) {
            RootShell.run(
                "mkdir -p \"$(dirname $MODULE_DIR$target)\"; " +
                    ": > \"$MODULE_DIR$target\"; chmod 755 \"$MODULE_DIR$target\"; echo ok"
            ).contains("ok")
        } else {
            RootShell.run("rm -f \"$MODULE_DIR$target\"; " +
                "if [ -f \"$target.bak\" ]; then cp \"$target.bak\" \"$target\" 2>/dev/null; fi; echo ok")
                .contains("ok")
        }
    }
}
