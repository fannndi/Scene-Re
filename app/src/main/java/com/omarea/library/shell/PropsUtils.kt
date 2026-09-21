package com.omarea.library.shell

import com.omarea.common.shell.KeepShellPublic

/**
 * Created by Hello on 2017/8/8.
 */

object PropsUtils {
    /**
     * Get property
     *
     * @param propName property name
     * @return content
     */
    fun getProp(propName: String): String {
        return KeepShellPublic.doCmdSync("getprop \"$propName\"")
    }

    fun setPorp(propName: String, value: String): Boolean {
        return KeepShellPublic.doCmdSync("setprop \"$propName\" \"$value\"") != "error"
    }
}
