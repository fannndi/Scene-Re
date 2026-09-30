package com.omarea.engine

import android.content.Context
import java.io.File

/**
 * Installs the "Scene Rescue" APatch/Magisk module from the bundled payload.
 *
 * The payload itself stays shell (a module installed into /data/adb/modules is
 * by definition shell); this object only orchestrates the copy + execution,
 * exactly like the old kr-script page did (same script, same unset option
 * variables).
 *
 * Responsibility: deploy + run the rescue installer.
 */
object ResurgenceInstaller {

    fun install(context: Context): String {
        val workDir = File(context.filesDir, "resurgence")
        workDir.mkdirs()
        copyPayload(context, workDir)
        return RootShell.run(
            "cd ${workDir.absolutePath} && PAGE_WORK_DIR=${context.filesDir.absolutePath} sh set.sh"
        ).trim()
    }

    fun isModuleInstalled(): Boolean =
        RootShell.run("[ -d /data/adb/modules/scene_resurgence ] && echo 1 || echo 0")
            .trim() == "1"

    private fun copyPayload(context: Context, target: File) {
        val names = context.assets.list("resurgence") ?: return
        for (name in names) {
            context.assets.open("resurgence/$name").use { input ->
                File(target, name).outputStream().use { input.copyTo(it) }
            }
            File(target, name).setExecutable(true, false)
        }
    }
}
