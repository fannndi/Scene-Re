package com.omarea.common.shell

import android.content.Context
import com.omarea.common.shared.ResourceStringResolver

// Resolve strings from resources to localize command output
class ShellTranslation(context: Context) : ResourceStringResolver(context) {
    fun getTranslatedResult(shellCommand: String, executor: KeepShell?): String {
        val shell = executor?: KeepShellPublic.getDefaultInstance()
        val rows = shell.doCmdSync(shellCommand).split("\n")
        return if (rows.isNotEmpty()) {
            resolveRows(rows)
        } else {
            ""
        }
    }
}