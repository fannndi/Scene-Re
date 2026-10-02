package com.omarea.runtime

import android.content.Context
import android.os.SystemClock
import com.omarea.common.shell.ShellLog
import com.omarea.util.PropsUtils

/**
 * Boot-order gate for the ROM's `init.qcom.post_boot.sh`.
 *
 * That script runs as a root `late_start` oneshot triggered by
 * `sys.boot_completed=1` and writes the very same node families the profile
 * engine owns (governor/min/max/hispeed, boosts, core_ctl, cpusets, sched,
 * LMK). Applying our tuning before it finishes means the ROM silently
 * overwrites it a moment later — so the boot apply waits for the service to
 * stop (bounded; a missing service counts as finished).
 *
 * Responsibility: bounded wait for the ROM post-boot service.
 * Non-goals: deciding what to apply (ModeSwitcher / ProfileController).
 */
object RomBootGate {

    const val SERVICE = "init.svc.qcom-post-boot"
    const val TIMEOUT_MS = 45_000L

    /** Pure decision: a missing/stopped service means post-boot is done. */
    fun isFinished(state: String?): Boolean {
        val value = state?.trim().orEmpty()
        return value.isEmpty() || value == "stopped"
    }

    /**
     * Waits until [SERVICE] reports stopped. Returns the waited time in ms when
     * it finished, or null when the timeout hit (caller proceeds anyway).
     */
    fun await(context: Context, timeoutMs: Long = TIMEOUT_MS): Long? {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            val state = runCatching { PropsUtils.getProp(SERVICE) }.getOrNull()
            if (isFinished(state)) {
                val waited = SystemClock.elapsedRealtime() - start
                ShellLog.log("RomBootGate", "post-boot finished (waited ${waited}ms)")
                return waited
            }
            try {
                Thread.sleep(1000L)
            } catch (_: InterruptedException) {
                return null
            }
        }
        ShellLog.log("RomBootGate", "post-boot still running after ${timeoutMs}ms — applying anyway", error = true)
        return null
    }
}
