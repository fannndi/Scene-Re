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

    const val PERFBOOSTS = "/system/vendor/etc/perf/perfboostsconfig.xml"
    const val PERFD = "/system/vendor/bin/perfd"

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
