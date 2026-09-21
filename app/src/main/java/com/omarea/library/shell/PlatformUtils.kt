package com.omarea.library.shell

/**
 * Read processor platform
 * Created by helloklf on 2017/6/3.
 */

class PlatformUtils {
    companion object {
        private var cpu: String? = null
    }

    // get CPU model, e.g. msm8996
    fun getCPUName(): String {
        if (cpu == null) {
            cpu = PropsUtils.getProp("ro.board.platform")
        }

        return cpu!!
    }
}
