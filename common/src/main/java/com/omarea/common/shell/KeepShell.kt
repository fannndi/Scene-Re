@file:OptIn(DelicateCoroutinesApi::class)

package com.omarea.common.shell

import android.util.Log
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.*
import java.util.concurrent.locks.ReentrantLock


/**
 * Created by Hello on 2018/01/23.
 */
public class KeepShell(private var rootMode: Boolean = true) {
    private var p: Process? = null
    private var out: OutputStream? = null
    private var reader: BufferedReader? = null
    private var currentIsIdle = true // Whether the shell is idle

    /**
     * The [ShellMode] the cached process was created for, or null when there is no process.
     *
     * A shell process is bound to one backend for its whole life: it was either spawned by the
     * app, by `su`, or by the Shizuku user service. Those have different uids and different SELinux
     * domains, so a process created for one mode must never be reused for another. Without this
     * field the cache had no way to notice a tier change, and the app would keep issuing commands
     * through a shell from the previous tier - which makes a Shizuku session silently behave like
     * the app's own uid, and is invisible except as unexplained permission denials.
     */
    private var shellMode: ShellMode? = null

    public val isIdle: Boolean
        get() {
            return currentIsIdle
        }

    // Try to exit the shell process
    public fun tryExit() {
        try {
            if (out != null)
                out!!.close()
            if (reader != null)
                reader!!.close()
        } catch (ex: Exception) {
        }
        try {
            p!!.destroy()
        } catch (ex: Exception) {
        }
        enterLockTime = 0L
        out = null
        reader = null
        p = null
        shellMode = null
        currentIsIdle = true
    }

    // Root acquisition timeout
    private val GET_ROOT_TIMEOUT = 20000L
    private val mLock = ReentrantLock()
    private val LOCK_TIMEOUT = 10000L
    private var enterLockTime = 0L

    private var checkRootState =
            // "if [[ \$(id -u 2>&1) == '0' ]] || [[ \$(\$UID) == '0' ]] || [[ \$(whoami 2>&1) == 'root' ]] || [[ \$(\$USER_ID) == '0' ]]; then\n" +
            "if [[ \$(id -u 2>&1) == '0' ]] || [[ \$(\$UID) == '0' ]] || [[ \$(whoami 2>&1) == 'root' ]] || [[ \$(set | grep 'USER_ID=0') == 'USER_ID=0' ]]; then\n" +
                    "  echo 'success'\n" +
                    "else\n" +
                    "if [[ -d /cache ]]; then\n" +
                    "  echo 1 > /cache/vtools_root\n" +
                    "  if [[ -f /cache/vtools_root ]] && [[ \$(cat /cache/vtools_root) == '1' ]]; then\n" +
                    "    echo 'success'\n" +
                    "    rm -rf /cache/vtools_root\n" +
                    "    return\n" +
                    "  fi\n" +
                    "fi\n" +
                    "exit 1\n" +
                    "exit 1\n" +
                    "fi\n"

    fun checkRoot(): Boolean {
        if (rootMode && ShellModeProvider.mode != ShellMode.ROOT) {
            // The current tier is not root; do not touch the running shell.
            return false
        }
        val r = doCmdSync(checkRootState).lowercase(Locale.getDefault())
        return if (r == "error" || r.contains("permission denied") || r.contains("not allowed") || r.equals("not found")) {
            if (rootMode) {
                tryExit()
            }
            false
        } else if (r.contains("success")) {
            true
        } else {
            if (rootMode) {
                tryExit()
            }
            false
        }
    }

    private fun getRuntimeShell() {
        val requestedMode = if (rootMode) ShellModeProvider.mode else ShellMode.NON_ROOT
        if (p != null) {
            if (shellMode == requestedMode) {
                return
            }
            // The tier changed under us. Drop the stale process and rebuild on the new backend;
            // reusing it would run commands with the previous tier's privileges.
            Log.d("KeepShell", "Shell mode changed $shellMode -> $requestedMode, restarting shell")
            tryExit()
        }
        val getSu = Thread(Runnable {
            try {
                mLock.lockInterruptibly()
                enterLockTime = System.currentTimeMillis()
                val created = if (rootMode) ShellExecutor.getPrivilegedRuntime() else ShellExecutor.getRuntime()
                // Record the mode before exposing the process. A caller that runs concurrently with
                // this thread must see a process and its mode together, otherwise it can observe a
                // process whose shellMode is still null and restart a shell that was just created.
                synchronized(this) {
                    p = created
                    shellMode = requestedMode
                    out = created.outputStream
                    reader = created.inputStream.bufferedReader()
                }
                if (rootMode && ShellModeProvider.mode == ShellMode.ROOT) {
                    out?.run {
                        write(checkRootState.toByteArray(Charset.defaultCharset()))
                        flush()
                    }
                }
                Thread(Runnable {
                    try {
                        val errorReader = created.errorStream.bufferedReader()
                        while (true) {
                            // After the process ends readLine() returns null; must break or this spins in a busy loop
                            val line = errorReader.readLine() ?: break
                            Log.e("KeepShellPublic", line)
                        }
                    } catch (ex: Exception) {
                        Log.e("c", "" + ex.message)
                    }
                }).start()
            } catch (ex: Exception) {
                Log.e("getRuntime", "" + ex.message)
                // Leave no half-built state behind: a null process with a stale mode would make the
                // next caller believe a shell exists for a backend it never connected to.
                synchronized(this) {
                    p = null
                    out = null
                    reader = null
                    shellMode = null
                }
            } finally {
                enterLockTime = 0L
                mLock.unlock()
            }
        })
        getSu.start()
        getSu.join(10000)
        if (p == null && getSu.state != Thread.State.TERMINATED) {
            enterLockTime = 0L
            getSu.interrupt()
        }
    }

    private var br = "\n\n".toByteArray(Charset.defaultCharset())

    private val shellOutputCache = StringBuilder()
    private val startTag = "|SH>>|"
    private val endTag = "|<<SH|"
    private val startTagBytes = "\necho '$startTag'\n".toByteArray(Charset.defaultCharset())
    private val endTagBytes = "\necho '$endTag'\n".toByteArray(Charset.defaultCharset())

    // Execute a script
    public fun doCmdSync(cmd: String): String {
        if (mLock.isLocked && enterLockTime > 0 && System.currentTimeMillis() - enterLockTime > LOCK_TIMEOUT) {
            tryExit()
            Log.e("doCmdSync-Lock", "Thread wait timed out ${System.currentTimeMillis()} - $enterLockTime > $LOCK_TIMEOUT")
        }
        getRuntimeShell()

        // Snapshot the streams under the same lock the shell thread uses, so a concurrent restart
        // cannot swap the process between the null check and the write.
        val outputStream = synchronized(this) { out }
        val inputReader = synchronized(this) { reader }

        try {
            mLock.lockInterruptibly()
            currentIsIdle = false

            outputStream?.run {
                GlobalScope.launch(Dispatchers.IO) {
                    write(startTagBytes)
                    write(cmd.toByteArray(Charset.defaultCharset()))
                    write(endTagBytes)
                    flush()
                }
            }

            var unstart = true
            while (true && inputReader != null) {
                val line = inputReader.readLine()
                if (line == null) {
                    break
                } else if (line.contains(endTag)) {
                    shellOutputCache.append(line.substring(0, line.indexOf(endTag)))
                    break
                } else if (line.contains(startTag)) {
                    shellOutputCache.clear()
                    shellOutputCache.append(line.substring(line.indexOf(startTag) + startTag.length))
                    unstart = false
                } else if (!unstart) {
                    shellOutputCache.append(line)
                    shellOutputCache.append("\n")
                }
            }
            // Log.e("shell-unlock", cmd)
            // Log.d("Shell", cmd.toString() + "\n" + "Result:"+results.toString().trim())
            return shellOutputCache.toString().trim()
        }
        catch (e: Exception) {
            tryExit()
            Log.e("KeepShellAsync", "" + e.message)
            return "error"
        } finally {
            enterLockTime = 0L
            mLock.unlock()

            currentIsIdle = true
        }
    }

    // Execute a script and resolve resource IDs in the result
    public fun doCmdSync(shellCommand: String, shellTranslation: ShellTranslation): String {
        val rows = doCmdSync(shellCommand).split("\n")
        if (rows.isNotEmpty()) {
            return shellTranslation.resolveRows(rows)
        } else {
            return ""
        }
    }
}
