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

    private var process: java.lang.Process? = null
    private var stdin: ParcelFileDescriptor? = null
    private var stdout: ParcelFileDescriptor? = null
    private var stderr: ParcelFileDescriptor? = null

    override fun getUid(): Int {
        return android.os.Process.myUid()
    }

    override fun openShell(command: Array<String>): Array<ParcelFileDescriptor> {
        closeShell()
        val child = ProcessBuilder(*command).redirectErrorStream(false).start()
        val stdinPipe = ParcelFileDescriptor.createReliablePipe()
        val stdoutPipe = ParcelFileDescriptor.createReliablePipe()
        val stderrPipe = ParcelFileDescriptor.createReliablePipe()

        process = child
        stdin = stdinPipe[1]
        stdout = stdoutPipe[0]
        stderr = stderrPipe[0]

        pump(ParcelFileDescriptor.AutoCloseInputStream(stdinPipe[0]), child.outputStream)
        pump(child.inputStream, ParcelFileDescriptor.AutoCloseOutputStream(stdoutPipe[1]))
        pump(child.errorStream, ParcelFileDescriptor.AutoCloseOutputStream(stderrPipe[1]))

        Log.d(TAG, "shell started, uid=" + android.os.Process.myUid())
        return arrayOf(stdinPipe[1], stdoutPipe[0], stderrPipe[0])
    }

    override fun closeShell() {
        try {
            process?.destroy()
        } catch (ex: Exception) {
            Log.d(TAG, "closeShell: " + ex.message)
        }
        process = null
        closeQuietly(stdin)
        closeQuietly(stdout)
        closeQuietly(stderr)
        stdin = null
        stdout = null
        stderr = null
    }

    override fun destroy() {
        closeShell()
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
