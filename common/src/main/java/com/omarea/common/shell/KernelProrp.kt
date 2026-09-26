package com.omarea.common.shell

/**
 * Reads and writes kernel parameter nodes
 * Created by Hello on 2017/11/01.
 */
object KernelProrp {
    /**
     * Reads a property
     * @param propName property name
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
     * Writes a property
     * @param propName property name (prefix with "persist." to keep it across reboots)
     * @param value    property value; prefer simple digits or letters to avoid errors
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
