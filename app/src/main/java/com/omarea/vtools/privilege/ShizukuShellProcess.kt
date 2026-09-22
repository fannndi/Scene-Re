package com.omarea.vtools.privilege

import android.os.ParcelFileDescriptor
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch

/**
 * [Process] facade over a shell hosted by [ShizukuShellService], so the existing
 * shell layer (KeepShell / KeepShellAsync) can use the Shizuku tier unchanged.
 */
class ShizukuShellProcess(
    private val service: IShizukuShellService,
    stdin: ParcelFileDescriptor,
    stdout: ParcelFileDescriptor,
    stderr: ParcelFileDescriptor
) : Process() {
    private val outputStream = ParcelFileDescriptor.AutoCloseOutputStream(stdin)
    private val inputStream = ParcelFileDescriptor.AutoCloseInputStream(stdout)
    private val errorStream = ParcelFileDescriptor.AutoCloseInputStream(stderr)
    private val exitLatch = CountDownLatch(1)
    private var destroyed = false

    override fun getOutputStream(): OutputStream {
        return outputStream
    }

    override fun getInputStream(): InputStream {
        return inputStream
    }

    override fun getErrorStream(): InputStream {
        return errorStream
    }

    override fun waitFor(): Int {
        exitLatch.await()
        return 0
    }

    override fun exitValue(): Int {
        return 0
    }

    override fun destroy() {
        synchronized(this) {
            if (destroyed) {
                return
            }
            destroyed = true
        }
        try {
            outputStream.close()
            inputStream.close()
            errorStream.close()
        } catch (ex: Exception) {
        }
        try {
            service.closeShell()
        } catch (ex: Exception) {
        }
        exitLatch.countDown()
    }
}
