package com.omarea.common.shell

/**
 * Operate on kernel parameter nodes
 * Created by Hello on 2017/11/01.
 */
object KernelProrp {
    /**
     * Get a property
     * @param propName property name
     * @return
     */
    fun getProp(propName: String): String {
        return KeepShellPublic.doCmdSync("if [[ -e \"$propName\" ]]; then cat \"$propName\"; fi;")
    }

    fun getProp(propName: String, grep: String): String {
        return KeepShellPublic.doCmdSync("if [[ -e \"$propName\" ]]; then cat \"$propName\" | grep \"$grep\"; fi;")
    }

    /**
     * Save a property
     * @param propName property name (prefix with persist. to save permanently)
     * @param value    property value; keep it to simple digits or letters to avoid errors
     */
    fun setProp(propName: String, value: String): Boolean {
        return KeepShellPublic.doCmdSync(
                "chmod 664 \"$propName\" 2 > /dev/null\n" +
                "echo \"$value\" > \"$propName\""
        ) != "error"
    }
}