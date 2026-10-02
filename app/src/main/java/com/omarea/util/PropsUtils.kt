package com.omarea.util

import com.omarea.common.shell.KeepShellPublic

/**
 * Created by Hello on 2017/8/8.
 */

object PropsUtils {
    /**
     * 获取属性
     *
     * Root shell first (works even when the app's own context cannot read the
     * property service). In Monitor mode (no root) the shell is dead and
     * returns empty — then `getprop` is executed directly, which works fine
     * from an untrusted app and keeps UI paths (platform detection, drafts)
     * alive without root.
     *
     * @param propName 属性名称
     * @return 内容
     */
    fun getProp(propName: String): String {
        val viaShell = KeepShellPublic.doCmdSync("getprop \"$propName\"")
        if (viaShell.isNotBlank() && viaShell != "error") return viaShell
        return getPropDirect(propName)
    }

    private fun getPropDirect(propName: String): String = try {
        val process = Runtime.getRuntime().exec(arrayOf("/system/bin/getprop", propName))
        val text = process.inputStream.bufferedReader().use { it.readText() }.trim()
        process.destroy()
        text
    } catch (ex: Exception) {
        ""
    }

    fun setPorp(propName: String, value: String): Boolean {
        return KeepShellPublic.doCmdSync("setprop \"$propName\" \"$value\"") != "error"
    }
}
