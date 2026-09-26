package com.omarea.library.shell

/**
 * Reads the processor platform
 * Created by helloklf on 2017/6/3.
 */

class PlatformUtils {
    companion object {
        private var cpu: String? = null
    }

    // Get the CPU model, e.g. msm8996
    fun getCPUName(): String {
        if (cpu == null) {
            cpu = PropsUtils.getProp("ro.board.platform")
        }

        return cpu!!
    }
}
