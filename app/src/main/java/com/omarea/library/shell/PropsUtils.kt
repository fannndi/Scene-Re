package com.omarea.library.shell

import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.ShellEscape

/**
 * Created by Hello on 2017/8/8.
 */

object PropsUtils {
    /**
     * 获取属性
     *
     * @param propName 属性名称
     * @return 内容
     */
    fun getProp(propName: String): String {
        // Single-quote the name so a value containing a space, a quote or a
        // newline cannot terminate the argument and start a new command.
        return KeepShellPublic.doCmdSync("getprop " + ShellEscape.quote(propName))
    }

    fun setProp(propName: String, value: String): Boolean {
        return KeepShellPublic.doCmdSync(
                "setprop " + ShellEscape.quote(propName) + " " + ShellEscape.quote(value)
        ) != "error"
    }
}
