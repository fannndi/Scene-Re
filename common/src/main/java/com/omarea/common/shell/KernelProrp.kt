package com.omarea.common.shell

/**
 * 操作内核参数节点
 * Created by Hello on 2017/11/01.
 */
object KernelProrp {
    /**
     * 获取属性
     * @param propName 属性名称
     * @return
     */
    fun getProp(propName: String): String {
        if (!ShellEscape.isSafePath(propName)) {
            return ""
        }
        val path = ShellEscape.quote(propName)
        return KeepShellPublic.doCmdSync("if [[ -e $path ]]; then cat $path; fi;")
    }

    fun getProp(propName: String, grep: String): String {
        if (!ShellEscape.isSafePath(propName)) {
            return ""
        }
        val path = ShellEscape.quote(propName)
        // `grep -F` keeps the pattern literal, so a value containing regex
        // metacharacters cannot change which lines are matched.
        val pattern = ShellEscape.quote(grep)
        return KeepShellPublic.doCmdSync("if [[ -e $path ]]; then cat $path | grep -F $pattern; fi;")
    }

    /**
     * 保存属性
     * @param propName 属性名称（要永久保存，请以persist.开头）
     * @param value    属性值,值尽量是简单的数字或字母，避免出现错误
     */
    fun setProp(propName: String, value: String): Boolean {
        if (!ShellEscape.isSafePath(propName)) {
            return false
        }
        val path = ShellEscape.quote(propName)
        // Quoting the value is what prevents a newline or quote in it from being
        // interpreted as a second command; previously it was interpolated raw.
        val quotedValue = ShellEscape.quote(value)
        return KeepShellPublic.doCmdSync(
                "chmod 664 $path 2>/dev/null\n" +
                "echo $quotedValue > $path"
        ) != "error"
    }
}
