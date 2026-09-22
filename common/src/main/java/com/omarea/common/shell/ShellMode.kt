package com.omarea.common.shell

/**
 * Privilege tier used for all shell commands.
 *
 * ROOT     - commands run through `su` (uid 0).
 * SHIZUKU  - commands run through a shell process hosted by a Shizuku user service (usually uid 2000).
 * NON_ROOT - commands run through the app's own `sh` (app uid); read-only for most sysfs paths.
 */
enum class ShellMode {
    ROOT,
    SHIZUKU,
    NON_ROOT
}

/**
 * Provides a shell process for the SHIZUKU tier. The app module registers an implementation
 * because only it can depend on the Shizuku API.
 */
interface ShizukuShellProvider {
    /** Returns a ready shell process or null when the Shizuku service is not connected. */
    fun createShell(): Process?
}

/**
 * Global shell routing state. The app module updates this whenever the user changes the
 * privilege tier; the shell layer only reads it.
 */
object ShellModeProvider {
    @Volatile
    var mode: ShellMode = ShellMode.ROOT

    @Volatile
    var shizukuShellProvider: ShizukuShellProvider? = null
}
