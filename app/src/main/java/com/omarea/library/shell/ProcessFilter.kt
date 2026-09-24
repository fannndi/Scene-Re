package com.omarea.library.shell

import com.omarea.model.ProcessInfo

/**
 * Classification helpers shared by the process list UIs
 * ([com.omarea.ui.AdapterProcess], [com.omarea.ui.AdapterProcessMini],
 * [com.omarea.vtools.activities.ActivityProcess]).
 *
 * Previously each of those classes carried its own verbatim copy of these
 * regexes and predicates; a fix in one place silently diverged from the others.
 *
 * **Terminology, because the names do not say what they mean:**
 *
 * `ProcessInfo.command` is the `COMMAND` column of
 * `ps -e -o %CPU,RES,SWAP,NAME,PID,USER,COMMAND,CMDLINE` — i.e. argv[0], not the
 * full cmdline (that is `ProcessInfo.cmdline`). Any process forked from zygote
 * reports `/system/bin/app_process` or `app_process64` there, so
 * [isAndroidProcess] answers "is this a runtime-managed process at all".
 *
 * [isSystemProcess] therefore means **"an Android process the user did not
 * install"**, not "a kernel daemon". `system_server` and built-in apps such as
 * `com.android.settings` are system processes by this definition, which is what
 * the `System applications` entry in the process-filter dropdown labels. Kernel
 * threads (`[kworker/0:1]`, `init`) fall under [isOtherProcess] / `Other
 * processes` instead — they fail [isAndroidProcess] because their `command` is
 * their own bracketed name rather than `app_process`.
 */
object ProcessFilter {
    /** Android runs app processes as `u<userId>_a<appId>`; system daemons use other users. */
    private val REGEX_APP_USER = Regex("u[0-9]+_.*")

    /**
     * A runtime-managed process is one forked from zygote, which reports
     * `app_process` in the `COMMAND` column. Its *name* is either a package name
     * (`com.android.settings`) or a runtime thread name (`system_server`).
     */
    private val REGEX_APP_PROCESS = Regex("app_process[0-9]*")

    /** Bare runtime thread names that are not package names. */
    private val RUNTIME_THREAD_NAMES = setOf("system_server", "zygote", "zygote64")

    /**
     * True for a process forked from zygote.
     *
     * Note this is broader than "belongs to an APK": `system_server` and the
     * zygotes match as well. Callers that need a *package* name — resolving an
     * icon, or `am force-stop`ping an app — must use [isPackageProcess] instead,
     * because those names are not packages.
     */
    @JvmStatic
    fun isAndroidProcess(processInfo: ProcessInfo): Boolean {
        // Deliberately checked against the command column rather than the name:
        // `system_server` is a real app_process process but has no dot in its name.
        val command = processInfo.command
        if (command.isEmpty()) {
            return false
        }
        return REGEX_APP_PROCESS.containsMatchIn(command)
    }

    /**
     * True for an [isAndroidProcess] whose name is a package name, i.e. one that
     * `am`/`killall` can act on directly.
     *
     * `system_server` and the zygotes fail this on purpose: they are runtime
     * infrastructure, not installable apps.
     */
    @JvmStatic
    fun isPackageProcess(processInfo: ProcessInfo): Boolean {
        if (!isAndroidProcess(processInfo)) return false
        val name = processInfo.name
        return name.contains('.') && !RUNTIME_THREAD_NAMES.contains(name.split(':').first())
    }

    /** Android process running under a non-app UID (i.e. a system_service). */
    @JvmStatic
    fun isSystemProcess(processInfo: ProcessInfo): Boolean {
        return isAndroidProcess(processInfo) && !isUserProcess(processInfo)
    }

    /** Android process running under an app UID. */
    @JvmStatic
    fun isUserProcess(processInfo: ProcessInfo): Boolean {
        return processInfo.user.matches(REGEX_APP_USER)
    }

    /** Any process that is not part of the Android app runtime. */
    @JvmStatic
    fun isOtherProcess(processInfo: ProcessInfo): Boolean {
        return !isAndroidProcess(processInfo)
    }
}
