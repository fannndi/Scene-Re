package com.omarea.common.shell

/**
 * Definitive "su is gone" signal.
 *
 * [ShellExecutor.getSuperUserRuntime] fails at the exec(2) level only when the
 * `su` binary is missing or not executable — a different condition from "the
 * user denied the request" (su spawns, but the privilege check fails). The app
 * uses this signal to tell MISSING from DENIED without guessing from output.
 *
 * Responsibility: one thread-safe callback holder.
 * Non-goals: state machine / persistence (see `CheckRootStatus`).
 */
object SuperUserSignal {

    /** Invoked on the calling thread; keep it cheap and non-blocking. */
    @Volatile
    var onMissing: (() -> Unit)? = null

    fun reportMissing() {
        onMissing?.invoke()
    }
}
