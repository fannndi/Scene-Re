package com.omarea.vtools.privilege

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.Keep
import java.io.InputStream
import java.io.OutputStream

/**
 * Shell host that runs inside a process created by the Shizuku server (usually as uid 2000).
 *
 * The Shizuku server instantiates this class by name, so it is not an Android Service and must
 * keep a public constructor. All shell commands executed through the Shizuku tier are spawned here.
 */
class ShizukuShellService : IShizukuShellService.Stub {
    companion object {
        private const val TAG = "SceneShizukuShell"
    }

    @Keep
    constructor() : super()

    @Keep
    constructor(context: Context) : super()

    private class Shell(
        val process: java.lang.Process,
        val stdin: ParcelFileDescriptor,
        val stdout: ParcelFileDescriptor,
        val stderr: ParcelFileDescriptor
    )

    /**
     * Live shells, keyed by id.
     *
     * The app keeps more than one persistent shell (KeepShellPublic's default and secondary
     * instances), so this host must support several at once. It used to hold a single process and
     * destroy it on every openShell(), which killed whichever shell was already running: that
     * shell's reader hit end-of-stream and every later command returned empty output even though
     * earlier commands had succeeded.
     */
    private val shells = HashMap<Int, Shell>()
    private var nextShellId = 1

    override fun getUid(): Int {
        return android.os.Process.myUid()
    }

    override fun openShell(command: Array<String>, shellId: IntArray): Array<ParcelFileDescriptor> {
        val child = ProcessBuilder(*command).redirectErrorStream(false).start()
        val stdinPipe = ParcelFileDescriptor.createReliablePipe()
        val stdoutPipe = ParcelFileDescriptor.createReliablePipe()
        val stderrPipe = ParcelFileDescriptor.createReliablePipe()

        val id: Int
        synchronized(shells) {
            id = nextShellId++
            shells[id] = Shell(child, stdinPipe[1], stdoutPipe[0], stderrPipe[0])
        }
        if (shellId.isNotEmpty()) {
            shellId[0] = id
        }

        pump(ParcelFileDescriptor.AutoCloseInputStream(stdinPipe[0]), child.outputStream)
        pump(child.inputStream, ParcelFileDescriptor.AutoCloseOutputStream(stdoutPipe[1]))
        pump(child.errorStream, ParcelFileDescriptor.AutoCloseOutputStream(stderrPipe[1]))

        Log.d(TAG, "shell $id started, uid=" + android.os.Process.myUid() + ", live=" + shells.size)
        return arrayOf(stdinPipe[1], stdoutPipe[0], stderrPipe[0])
    }

    override fun closeShell(shellId: Int) {
        val shell = synchronized(shells) { shells.remove(shellId) }
        if (shell == null) {
            return
        }
        try {
            shell.process.destroy()
        } catch (ex: Exception) {
            Log.d(TAG, "closeShell($shellId): " + ex.message)
        }
        closeQuietly(shell.stdin)
        closeQuietly(shell.stdout)
        closeQuietly(shell.stderr)
    }

    override fun destroy() {
        val open = synchronized(shells) {
            val copy = shells.keys.toList()
            copy
        }
        for (id in open) {
            closeShell(id)
        }
        System.exit(0)
    }

    private fun closeQuietly(descriptor: ParcelFileDescriptor?) {
        try {
            descriptor?.close()
        } catch (ex: Exception) {
        }
    }

    private fun pump(input: InputStream, output: OutputStream) {
        val thread = Thread {
            try {
                val buffer = ByteArray(4096)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    output.write(buffer, 0, read)
                    output.flush()
                }
            } catch (ex: Exception) {
                Log.d(TAG, "pump stopped: " + ex.message)
            } finally {
                try {
                    output.close()
                } catch (ex: Exception) {
                }
            }
        }
        thread.isDaemon = true
        thread.start()
    }
}
