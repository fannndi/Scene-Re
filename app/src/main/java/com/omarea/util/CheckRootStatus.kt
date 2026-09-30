@file:OptIn(DelicateCoroutinesApi::class)

package com.omarea.util

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import androidx.core.content.PermissionChecker
import com.omarea.Scene
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.ui.DialogHelper
import com.omarea.util.CommonCmds
import com.omarea.vtools.R
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

/**
 * 检查获取root权限
 * Created by helloklf on 2017/6/3.
 */

public class CheckRootStatus(var context: Context, private val next: Runnable? = null, private var disableSeLinux: Boolean = false, private val skip: Runnable? = null) {
    var myHandler: Handler = Handler(Looper.getMainLooper())

    var therad: Thread? = null
    public fun forceGetRoot() {
        if (lastCheckResult) {
            if (next != null) {
                myHandler.post(next)
            }
        } else {
            // Atomic: the check thread and the 15s watchdog both touch this.
            val completed = java.util.concurrent.atomic.AtomicBoolean(false)
            therad = Thread {
                setRootStatus(KeepShellPublic.checkRoot())

                if (!completed.compareAndSet(false, true)) {
                    return@Thread
                }

                if (lastCheckResult) {
                    if (disableSeLinux) {
                        KeepShellPublic.doCmdSync(CommonCmds.DisableSELinux)
                    }
                    if (next != null) {
                        myHandler.post(next)
                    }
                } else {
                    myHandler.post {
                        KeepShellPublic.tryExit()

                        val view = LayoutInflater.from(context).inflate(R.layout.dialog_root_rejected, null)
                        DialogHelper.customDialog(context, view, false).apply {
                            view.findViewById<View>(R.id.btn_retry).setOnClickListener {
                                dismiss()

                                KeepShellPublic.tryExit()
                                if (therad != null && therad!!.isAlive && !therad!!.isInterrupted) {
                                    therad!!.interrupt()
                                    therad = null
                                }
                                forceGetRoot()
                            }
                            view.findViewById<View>(R.id.btn_skip).setOnClickListener {
                                dismiss()
                                skip?.run {
                                    myHandler.post(skip)
                                }
                                //android.os.Process.killProcess(android.os.Process.myPid())
                            }
                        }

                    }
                }
            }
            therad!!.start()
            Thread {
                Thread.sleep(1000 * 15)

                if (!completed.get()) {
                    KeepShellPublic.tryExit()
                    myHandler.post {
                        val view = LayoutInflater.from(context).inflate(R.layout.dialog_root_timeout, null)
                        DialogHelper.customDialog(context, view, false).apply {
                            view.findViewById<View>(R.id.btn_retry).setOnClickListener {
                                if (therad != null && therad!!.isAlive && !therad!!.isInterrupted) {
                                    therad!!.interrupt()
                                    therad = null
                                }
                                forceGetRoot()
                            }
                            view.findViewById<View>(R.id.btn_exit).setOnClickListener {
                                dismiss()

                                exitProcess(0)
                                //android.os.Process.killProcess(android.os.Process.myPid())
                            }
                        }
                    }
                }
            }.start()
        }
    }

    companion object {
        /**
         * Observable root state: Compose UI collects this so menus un-dim the
         * moment an async root check completes (previously a plain Boolean
         * read once at composition — the "Additional" menu stayed dead).
         */
        private val _rootStatus = MutableStateFlow(false)
        val rootStatus: StateFlow<Boolean> = _rootStatus.asStateFlow()

        /** Single-flight guard so concurrent checks don't race the dialogs. */
        private val checking = java.util.concurrent.atomic.AtomicBoolean(false)

        public fun checkRootAsync() {
            if (!checking.compareAndSet(false, true)) return
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    setRootStatus(KeepShellPublic.checkRoot())
                } finally {
                    checking.set(false)
                }
            }
        }

        // 最后的ROOT检测结果
        val lastCheckResult: Boolean
            get() {
                return _rootStatus.value
            }

        private fun setRootStatus(root: Boolean) {
            _rootStatus.value = root
            Scene.setBoolean("root", root)
        }
    }
}
