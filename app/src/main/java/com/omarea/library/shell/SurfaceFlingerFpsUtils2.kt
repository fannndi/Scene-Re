package com.omarea.library.shell

import com.omarea.common.shell.KeepShellPublic

class SurfaceFlingerFpsUtils2 {
    private var fpsCommand2 = "service call SurfaceFlinger 1013"
    private var lastTime = -1L
    private var lastFrames = -1
    private val keepShell = KeepShellPublic.getInstance("fps-watch", true)
    private val gfxInfoFpsUtils = GfxInfoFpsUtils(KeepShellPublic.getInstance("gfxinfo-fps", true))

    fun getFps(): Float {
        gfxInfoFpsUtils.getFps()?.let {
            return it
        }
        if (fpsCommand2.isNotEmpty()) {
            val result = keepShell.doCmdSync(fpsCommand2).trim()
            if (result != "error" && !result.contains("Parcel")) {
                fpsCommand2 = ""
            } else {
                try {
                    val index = result.indexOf("(") + 1
                    val frames = Integer.parseInt(result.substring(index, index + 8), 16)
                    val time = System.currentTimeMillis()
                    var fps = 0F
                    if (lastTime > 0 && lastFrames > 0) {
                        fps = (frames - lastFrames) * 1000.0f / (time - lastTime)
                    }
                    lastFrames = frames
                    lastTime = time
                    return fps
                } catch (ex: Exception) {
                    if (!(lastTime > 0 && lastFrames > 0)) {
                        fpsCommand2 = ""
                    }
                }
            }
        }
        return 1f
    }
}
