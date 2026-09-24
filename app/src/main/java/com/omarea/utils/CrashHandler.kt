package com.omarea.utils

import android.content.Context
import android.os.Environment
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.ShellEscape
import com.omarea.scene_mode.AlwaysNotification
import com.omarea.shell_utils.AppErrorLogcatUtils
import com.omarea.store.SpfConfig
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.Charset

/**
 * Created by Hello on 2017/5/24.
 */
class CrashHandler : Thread.UncaughtExceptionHandler {
    private var mContext: Context? = null
    private var mDefaultHandler: Thread.UncaughtExceptionHandler? = null

    fun init(ctx: Context) {
        mContext = ctx
        mDefaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, ex: Throwable) {
        // Central record first, so the crash lands in the same correlated log as the
        // feature events that led up to it. SceneLog never throws, so this is safe
        // even on the crashing thread.
        SceneLog.e("Crash", "uncaught exception on thread '${thread.name}'", ex)

        if (ex.message != null) {
            try {
                val trace = StringWriter()
                ex.printStackTrace(PrintWriter(trace))
                val fileOutputStream = FileOutputStream(
                        File(Environment.getExternalStorageDirectory().absolutePath + "/Android/vtools-error.log"))
                fileOutputStream.write((ex.message + "\n\n" + trace.toString()).toByteArray(Charset.defaultCharset()))
                fileOutputStream.flush()
                fileOutputStream.close()
            } catch (ex: Exception) {
            }
        }
        AppErrorLogcatUtils().catLogInfo2File(android.os.Process.myPid())
        // Persist the in-memory buffer next to the crash report, so the events that
        // preceded the crash survive the process death.
        runCatching {
            SceneLog.logFilePath()?.let { path ->
                File(path).parentFile?.let { dir ->
                    if (!dir.exists()) dir.mkdirs();
                    File(dir, "scene-log-crash.txt").writeText(SceneLog.dump())
                }
            }
        }
        mContext?.run {
            try {
                if (mContext != null) {
                    AlwaysNotification(mContext!!, true).hideNotify()
                }
            } catch (ex: Exception) {
            }
            if (getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE).getBoolean(SpfConfig.GLOBAL_SPF_AUTO_EXIT, true)) {
                val serviceHelper = AccessibleServiceHelper()
                if (serviceHelper.serviceRunning(mContext!!)) {
                    serviceHelper.stopSceneModeService(mContext!!)
                }
                KeepShellPublic.doCmdSync(
                    ShellEscape.cmd("killall", "-9", packageName) + " || " +
                            ShellEscape.cmdLine("am", "force-stop", packageName)
                )

                // Thread.setDefaultUncaughtExceptionHandler(mDefaultHandler)
                // throw ex
            } else {
                // val am = getSystemService (Context.ACTIVITY_SERVICE) as ActivityManager
                // am.restartPackage(getPackageName());
            }
        }
    }
}
