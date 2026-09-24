package com.omarea.library.shell

import com.omarea.common.shell.KeepShellPublic

/**
 * Read-only filesystem probes for the instrumentation tests.
 *
 * `RootFile` itself is a production class with a wide API (list, delete,
 * md5-compare); the tests only need to *ask questions* about the device and must
 * never mutate it. Keeping the probes here makes that read-only intent explicit
 * and avoids the tests depending on production helpers growing side effects.
 *
 * Lives in `androidTest`, so it is never shipped in the APK.
 */
object RootFileTestProbe {

    fun dirExists(path: String): Boolean =
        KeepShellPublic.doCmdSync("if [[ -d \"$path\" ]]; then echo 1; fi").trim() == "1"

    fun fileExists(path: String): Boolean =
        KeepShellPublic.doCmdSync("if [[ -f \"$path\" ]]; then echo 1; fi").trim() == "1"

    fun fileNotEmpty(path: String): Boolean =
        KeepShellPublic.doCmdSync("if [[ -s \"$path\" ]]; then echo 1; fi").trim() == "1"

    /** Directory names under [path], excluding `.` and `..`. */
    fun listDirs(path: String): List<String> =
        KeepShellPublic.doCmdSync("ls -1 \"$path\" 2>/dev/null")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "error" }
            .filter { dirExists("$path/$it") }

    /** Entries under [path], excluding `.` and `..`. */
    fun list(path: String): List<String> =
        KeepShellPublic.doCmdSync("ls -1 \"$path\" 2>/dev/null")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "error" && it != "." && it != ".." }

    /** Raw contents of a node, or "" when it cannot be read. */
    fun read(path: String): String {
        val value = KeepShellPublic.doCmdSync("cat \"$path\" 2>/dev/null").trim()
        return if (value == "error") "" else value
    }
}
