package com.omarea.library.shell

import com.omarea.model.ProcessInfo

/**
 * Classification helpers shared by the process list UIs
 * ([com.omarea.ui.AdapterProcess], [com.omarea.ui.AdapterProcessMini],
 * [com.omarea.vtools.activities.ActivityProcess]).
 *
 * Previously each of those classes carried its own verbatim copy of these
 * regexes and predicates; a fix in one place silently diverged from the others.
 */
object ProcessFilter {
    /** Android runs app processes as `u<userId>_a<appId>`; system daemons use other users. */
    private val REGEX_APP_USER = Regex("u[0-9]+_.*")

    /** A real package name is dotted (`com.example.app`), which filters out init/kernel threads. */
    private val REGEX_PACKAGE_NAME = Regex(".*\\..*")

    /** True for processes started by the Android runtime (`app_process` with a dotted name). */
    @JvmStatic
    fun isAndroidProcess(processInfo: ProcessInfo): Boolean {
        return processInfo.command.contains("app_process") && processInfo.name.matches(REGEX_PACKAGE_NAME)
    }

    /** Android process running under a non-app UID (i.e. a system_service). */
    @JvmStatic
    fun isSystemProcess(processInfo: ProcessInfo): Boolean {
        return isAndroidProcess(processInfo) && !processInfo.user.matches(REGEX_APP_USER)
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
