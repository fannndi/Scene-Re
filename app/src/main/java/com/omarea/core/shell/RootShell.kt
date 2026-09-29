package com.omarea.core.shell

import com.omarea.common.shell.KeepShellPublic

/**
 * Single door to the root shell.
 *
 * Responsibility: run shell snippets and read sysfs nodes.
 * Non-goals: building commands (callers own their scripts).
 */
object RootShell {
    /** Runs [script] through the shared root shell and returns stdout. */
    fun run(script: String): String = KeepShellPublic.doCmdSync(script)

    /** Reads a single sysfs/proc node, trimmed. */
    fun read(node: String): String = KeepShellPublic.doCmdSync("cat $node").trim()

    /** Reads a system property, trimmed. */
    fun prop(name: String): String = KeepShellPublic.doCmdSync("getprop $name").trim()
}
