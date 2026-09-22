package com.omarea.utils

import android.content.Context
import android.os.Environment
import com.omarea.common.shell.KeepShellPublic
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
        mContext?.run {
            try {
                if (mContext != null) {
                    AlwaysNotification(mContext!!, true).hideNotify()
                }
            } catch (ex: Exception) {
            }
            if (getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE).getBoolean(SpfConfig.GLOBAL_SPF_AUTO_EXIT, true)) {
                // Two things must NOT happen on a crash, both for the same reason: the accessibility
                // grant must outlive the crash.
                //
                // 1. Do NOT call stopSceneModeService(). "Stopping" the service means removing it from
                //    the secure setting enabled_accessibility_services, which is a persistent,
                //    user-visible permission change, not a runtime teardown.
                // 2. Do NOT run "am force-stop $packageName". Android's AccessibilityManagerService
                //    revokes the accessibility grant when a package is force-stopped, so a force-stop
                //    here clears enabled_accessibility_services to null and accessibility_enabled to 0.
                //    Verified on the POCO X3 NFC (MIUI 13 / Android 12): `am force-stop com.omarea.vtools`
                //    alone resets both settings. That is exactly the "allowed accessibility, but it shows
                //    as not activated afterwards" report.
                //
                // A plain SIGKILL is enough: it ends this (already dying) process so the user is not left
                // staring at a frozen window, and the system tears down the service binding by itself.
                KeepShellPublic.doCmdSync("killall -9 $packageName")

                // Thread.setDefaultUncaughtExceptionHandler(mDefaultHandler)
                // throw ex
            } else {
                // val am = getSystemService (Context.ACTIVITY_SERVICE) as ActivityManager
                // am.restartPackage(getPackageName());
            }
        }
    }
}
