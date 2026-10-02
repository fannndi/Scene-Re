package com.omarea.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.omarea.common.shell.ShellLog
import com.omarea.engine.RootShell
import com.omarea.util.CheckRootStatus
import com.omarea.vtools.R

/**
 * Manual maintenance tools (AZenith-derived, docs/ATTRIBUTION.md).
 *
 *  - JIT compile: recompile every third-party package with `speed-profile`
 *    (Android's own compiler; one-shot, can take minutes).
 *  - fstrim: ask the platform to trim unused blocks (`sm fstrim`, fallback
 *    `vdc fstrim dotrim`).
 *
 * Both are explicit user actions, never automatic; both report via toast +
 * ShellLog. Root is required (nothing is written otherwise).
 *
 * Responsibility: run the two maintenance commands.
 * Non-goals: scheduling (user taps).
 */
object SystemTools {

    fun jitCompile(context: Context) {
        val app = context.applicationContext
        if (!CheckRootStatus.isAvailable()) {
            toast(app, R.string.settings_tools_no_root)
            return
        }
        Thread {
            val out = runCatching {
                RootShell.run(
                    "cmd package list packages -3 2>/dev/null | cut -d: -f2 | " +
                        "while read -r pkg; do [ -n \"\$pkg\" ] && cmd package compile -m speed-profile \"\$pkg\" >/dev/null 2>&1; done; echo done"
                ).trim()
            }.getOrDefault("")
            ShellLog.log("SystemTools", "jit compile: ${out.takeLast(80)}")
            toast(app, R.string.settings_jit_compile_done)
        }.start()
    }

    fun fstrim(context: Context) {
        val app = context.applicationContext
        if (!CheckRootStatus.isAvailable()) {
            toast(app, R.string.settings_tools_no_root)
            return
        }
        Thread {
            val out = runCatching {
                RootShell.run("sm fstrim >/dev/null 2>&1 || vdc fstrim dotrim >/dev/null 2>&1; echo done").trim()
            }.getOrDefault("")
            ShellLog.log("SystemTools", "fstrim: $out")
            toast(app, R.string.settings_fstrim_done)
        }.start()
    }

    private fun toast(context: Context, res: Int) {
        Handler(Looper.getMainLooper()).post {
            runCatching { Toast.makeText(context, res, Toast.LENGTH_LONG).show() }
        }
    }
}
