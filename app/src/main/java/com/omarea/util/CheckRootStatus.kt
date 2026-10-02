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
import android.widget.TextView
import androidx.core.content.PermissionChecker
import com.omarea.Scene
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.SuperUserSignal
import com.omarea.common.ui.DialogHelper
import com.omarea.data.SpfConfig
import com.omarea.util.CommonCmds
import com.omarea.vtools.R
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Root privilege condition, richer than a boolean.
 *
 *  - [RootState.AVAILABLE] — su works.
 *  - [RootState.MISSING]   — the `su` binary cannot even be spawned
 *    (ROM update / APatch removed). Definitive: see [SuperUserSignal].
 *  - [RootState.DENIED]    — su exists but the privilege check failed
 *    (prompt missed, request rejected, root manager removed the grant).
 *  - [RootState.UNKNOWN]   — not checked yet this session.
 */
enum class RootState { UNKNOWN, AVAILABLE, MISSING, DENIED }

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
                val state = performCheck()

                if (!completed.compareAndSet(false, true)) {
                    return@Thread
                }

                if (state == RootState.AVAILABLE) {
                    if (disableSeLinux) {
                        KeepShellPublic.doCmdSync(CommonCmds.DisableSELinux)
                    }
                    if (next != null) {
                        myHandler.post(next)
                    }
                } else {
                    myHandler.post {
                        KeepShellPublic.tryExit()
                        showUnavailableDialog(state)
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

    /**
     * Root missing / denied: honestly labeled dialog. MISSING (su binary
     * gone, e.g. after a ROM flash) offers "monitor mode" instead of an
     * endless retry loop; DENIED keeps the classic grant/retry flow.
     */
    private fun showUnavailableDialog(state: RootState) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_root_rejected, null)
        if (state == RootState.MISSING) {
            view.findViewById<TextView>(R.id.confirm_title).setText(R.string.error_root_missing)
            view.findViewById<TextView>(R.id.confirm_message).setText(R.string.error_root_missing_desc)
            view.findViewById<TextView>(R.id.btn_skip).setText(R.string.btn_continue_monitor)
        }
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

    companion object {
        /**
         * Observable root state: Compose UI collects this so menus un-dim the
         * moment an async root check completes (previously a plain Boolean
         * read once at composition — the "Additional" menu stayed dead).
         */
        private val _rootStatus = MutableStateFlow(false)
        val rootStatus: StateFlow<Boolean> = _rootStatus.asStateFlow()

        /** Rich state (MISSING vs DENIED); Home/Diagnostics collect this too. */
        private val _rootState = MutableStateFlow(RootState.UNKNOWN)
        val rootState: StateFlow<RootState> = _rootState.asStateFlow()

        /**
         * Optional listener for the running app: fires when the state actually
         * changes (mid-session root loss included). Keep cheap; publish() can
         * run on any thread (e.g. the su-spawn failure in ShellExecutor).
         */
        @Volatile
        var onStateChanged: ((RootState) -> Unit)? = null

        /** Set while a `su` spawn failure is observed (MISSING vs DENIED). */
        private val suMissing = AtomicBoolean(false)

        /** Single-flight guard so concurrent checks don't race the dialogs. */
        private val checking = java.util.concurrent.atomic.AtomicBoolean(false)

        init {
            // Definitive signal from ShellExecutor: su cannot even be spawned.
            // Runs on whatever thread hit the exec failure. A spawn failure
            // alone is not enough to declare MISSING — a transient failure on
            // a rooted device must not auto-disable the engine, so an
            // AVAILABLE/DENIED state triggers a re-verification instead.
            SuperUserSignal.onMissing = {
                suMissing.set(true)
                when (_rootState.value) {
                    RootState.UNKNOWN -> publish(RootState.MISSING)
                    RootState.AVAILABLE, RootState.DENIED -> checkRootAsync()
                    RootState.MISSING -> Unit
                }
            }
        }

        /** Cached value; no shell I/O. */
        fun currentRootState(): RootState = _rootState.value

        /** True only when the last check proved su works. */
        fun isAvailable(): Boolean = _rootState.value == RootState.AVAILABLE

        /**
         * Quiet root check (no dialogs) for boot/services. Single-flight;
         * returns the current state when another check is in progress.
         * Bounded: a su that waits for an approval prompt can never stall the
         * boot worker — the timeout counts as DENIED (session without root).
         */
        public fun checkRootQuietly(timeoutMs: Long = 12_000L): RootState {
            if (!checking.compareAndSet(false, true)) return _rootState.value
            try {
                val result = arrayOfNulls<RootState>(1)
                val thread = Thread { result[0] = performCheck() }
                thread.isDaemon = true
                thread.start()
                thread.join(timeoutMs)
                if (thread.isAlive) {
                    // Unblock the read loop by closing the shell streams.
                    KeepShellPublic.tryExit()
                    thread.interrupt()
                    val state = if (suMissing.get()) RootState.MISSING else RootState.DENIED
                    publish(state)
                    return state
                }
                return result[0] ?: _rootState.value
            } finally {
                checking.set(false)
            }
        }

        public fun checkRootAsync() {
            if (!checking.compareAndSet(false, true)) return
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    performCheck()
                } finally {
                    checking.set(false)
                }
            }
        }

        /** Blocking check; updates state + legacy bool + persistence. */
        private fun performCheck(): RootState {
            suMissing.set(false)
            val ok = try {
                KeepShellPublic.checkRoot()
            } catch (ex: Exception) {
                false
            }
            val state = classify(ok, suMissing.get())
            publish(state)
            return state
        }

        /** Pure decision: which state a check outcome maps to (JVM-tested). */
        internal fun classify(ok: Boolean, suMissing: Boolean): RootState = when {
            ok -> RootState.AVAILABLE
            suMissing -> RootState.MISSING
            else -> RootState.DENIED
        }

        private fun publish(state: RootState) {
            val changed = _rootState.value != state
            if (changed) {
                _rootState.value = state
            }
            _rootStatus.value = state == RootState.AVAILABLE
            // Legacy boolean kept for existing callers/readers.
            Scene.setBoolean("root", state == RootState.AVAILABLE)
            Scene.globalConfig.edit()
                .putString(SpfConfig.GLOBAL_SPF_ROOT_STATE, state.name)
                .putLong(SpfConfig.GLOBAL_SPF_ROOT_CHECKED_AT, System.currentTimeMillis())
                .apply()
            if (changed) {
                runCatching { onStateChanged?.invoke(state) }
            }
        }

        // 最后的ROOT检测结果
        val lastCheckResult: Boolean
            get() {
                return _rootStatus.value
            }

        /** Human label for banners/Diagnostics. */
        fun describe(context: Context? = null): String {
            val checkedAt = try {
                Scene.globalConfig.getLong(SpfConfig.GLOBAL_SPF_ROOT_CHECKED_AT, 0L)
            } catch (ex: Exception) {
                0L
            }
            val whenText = if (checkedAt > 0) {
                java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(checkedAt))
            } else "never"
            return when (_rootState.value) {
                RootState.AVAILABLE -> "available"
                RootState.MISSING -> "missing (su tidak ada)"
                RootState.DENIED -> "denied (izin ditolak)"
                RootState.UNKNOWN -> "unchecked"
            } + " · last check $whenText"
        }
    }
}
