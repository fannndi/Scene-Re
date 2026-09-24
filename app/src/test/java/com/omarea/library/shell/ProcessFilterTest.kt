package com.omarea.library.shell

import com.omarea.model.ProcessInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Process classification drives which list a process appears in (app / system /
 * other) and, indirectly, which processes the freeze feature offers. The rules
 * were previously duplicated across three adapters; this verifies the shared
 * implementation matches the intended semantics.
 */
/**
 * Process classification drives which list a process appears in, and the
 * `General Application` / `System applications` filter in the process manager.
 *
 * Two things are worth knowing before reading these tests, because both are
 * easy to get backwards:
 *
 * - **`command` is the `COMMAND` column of `ps -e -o ...,COMMAND,CMDLINE`**
 *   (see `ProcessUtils.readRow`), i.e. argv\[0\] — *not* the cmdline. Any process
 *   forked from zygote reports `app_process`/`app_process64` there, which is what
 *   makes [ProcessFilter.isAndroidProcess] a useful answer to "is this managed by
 *   the Android runtime at all".
 * - **[ProcessFilter.isSystemProcess] means "a runtime process the user did not
 *   install"**, not "a kernel daemon". `system_server` *is* a system process by
 *   this definition. Kernel threads (`[kworker/0:1]`, `init`) fall under
 *   `Other processes` via [ProcessFilter.isOtherProcess] instead.
 *
 * A third distinction matters for correctness: `system_server` is a runtime
 * process but *not* a package, so it must fail [ProcessFilter.isPackageProcess]
 * (that is what `am force-stop` / icon lookup need).
 */
class ProcessFilterTest {

    private fun process(
        name: String,
        user: String = "u0_a123",
        command: String = "app_process"
    ) = ProcessInfo().apply {
        this.name = name
        this.user = user
        this.command = command
    }

    /** As reported by `ps` on a real device for a zygote-forked process. */
    private fun runtimeProcess(name: String, user: String) =
        process(name, user, "/system/bin/app_process64")

    // --- isAndroidProcess: forked from zygote ------------------------------

    @Test
    fun `dotted name under app_process is an android process`() {
        assertTrue(ProcessFilter.isAndroidProcess(process("com.example.app")))
    }

    @Test
    fun `app_process with an undotted name is still an android process`() {
        // Regression: this used to be false, because the rule required a dot in
        // the name. system_server has no dot but is runtime-managed.
        assertTrue(ProcessFilter.isAndroidProcess(runtimeProcess("system_server", "system")))
    }

    @Test
    fun `dotted name without app_process is not an android process`() {
        // e.g. a native daemon
        assertFalse(ProcessFilter.isAndroidProcess(process("com.example.native", command = "./daemon")))
    }

    @Test
    fun `a kernel thread is not an android process`() {
        assertFalse(
            ProcessFilter.isAndroidProcess(
                process("[kworker/0:1]", user = "root", command = "[kworker/0:1]")
            )
        )
    }

    @Test
    fun `an empty command is not an android process`() {
        assertFalse(ProcessFilter.isAndroidProcess(process("com.example.app", command = "")))
    }

    @Test
    fun `app_process64 is recognised`() {
        assertTrue(ProcessFilter.isAndroidProcess(runtimeProcess("com.example.app", "u0_a123")))
    }

    // --- isPackageProcess --------------------------------------------------

    @Test
    fun `an installed app is a package process`() {
        assertTrue(ProcessFilter.isPackageProcess(process("com.example.app", user = "u0_a123")))
    }

    @Test
    fun `a sub-process of an app is a package process`() {
        // com.example.app:remote — the colon suffix does not make it unofficial.
        assertTrue(
            ProcessFilter.isPackageProcess(process("com.example.app:remote", user = "u0_a123"))
        )
    }

    @Test
    fun `system_server is not a package process`() {
        // It is runtime infrastructure; `am force-stop system_server` is nonsense.
        assertFalse(ProcessFilter.isPackageProcess(runtimeProcess("system_server", "system")))
    }

    @Test
    fun `zygote is not a package process`() {
        assertFalse(ProcessFilter.isPackageProcess(runtimeProcess("zygote64", "root")))
    }

    @Test
    fun `a kernel thread is not a package process`() {
        assertFalse(
            ProcessFilter.isPackageProcess(
                process("[kworker/0:1]", user = "root", command = "[kworker/0:1]")
            )
        )
    }

    @Test
    fun `every package process is an android process`() {
        val candidates = listOf(
            process("com.example.app"),
            runtimeProcess("com.android.settings", "system"),
            runtimeProcess("system_server", "system"),
            process("[kworker/0:1]", "root", "[kworker/0:1]")
        )
        for (candidate in candidates) {
            if (ProcessFilter.isPackageProcess(candidate)) {
                assertTrue(
                    "package process ${candidate.name} must also be an android process",
                    ProcessFilter.isAndroidProcess(candidate)
                )
            }
        }
    }

    // --- isUserProcess: app UID --------------------------------------------

    @Test
    fun `u0_a123 style user is an app user`() {
        assertTrue(ProcessFilter.isUserProcess(process("com.example.app", user = "u0_a123")))
    }

    @Test
    fun `u10_a456 style user is an app user`() {
        // Work-profile / secondary user app processes use a different user id.
        assertTrue(ProcessFilter.isUserProcess(process("com.example.app", user = "u10_a456")))
    }

    @Test
    fun `system user is not an app user`() {
        assertFalse(ProcessFilter.isUserProcess(runtimeProcess("system_server", "system")))
    }

    @Test
    fun `root user is not an app user`() {
        assertFalse(ProcessFilter.isUserProcess(process("init", user = "root")))
    }

    // --- isSystemProcess ---------------------------------------------------

    @Test
    fun `system_server is a system process`() {
        assertTrue(ProcessFilter.isSystemProcess(runtimeProcess("system_server", "system")))
    }

    @Test
    fun `a built-in app such as Settings is a system process`() {
        // UID 1000, installed in the system image: shown under "System applications".
        assertTrue(ProcessFilter.isSystemProcess(runtimeProcess("com.android.settings", "system")))
    }

    @Test
    fun `a normal app is not a system process`() {
        assertFalse(
            ProcessFilter.isSystemProcess(
                process("com.example.app", user = "u0_a123")
            )
        )
    }

    @Test
    fun `an undotted daemon is not a system process`() {
        assertFalse(
            ProcessFilter.isSystemProcess(
                process("kworker", user = "root", command = "[kworker]")
            )
        )
    }

    @Test
    fun `a kernel thread is reported as other, not system`() {
        // Kernel threads belong under "Other processes"; isSystemProcess must not
        // claim them.
        val kworker = process("[kworker/0:1]", user = "root", command = "[kworker/0:1]")
        assertFalse(ProcessFilter.isSystemProcess(kworker))
        assertTrue(ProcessFilter.isOtherProcess(kworker))
    }

    // --- isOtherProcess: the complement of isAndroidProcess -----------------

    @Test
    fun `a kernel thread is another process`() {
        assertTrue(ProcessFilter.isOtherProcess(process("kworker", command = "[kworker]")))
    }

    @Test
    fun `an app is not another process`() {
        assertFalse(ProcessFilter.isOtherProcess(process("com.example.app")))
    }

    // --- Pairwise consistency ----------------------------------------------

    @Test
    fun `system and user classifications are mutually exclusive`() {
        val systemServer = runtimeProcess("system_server", "system")
        assertTrue(ProcessFilter.isSystemProcess(systemServer))
        assertFalse(ProcessFilter.isUserProcess(systemServer))

        val app = process("com.example.app", user = "u0_a123")
        assertTrue(ProcessFilter.isUserProcess(app))
        assertFalse(ProcessFilter.isSystemProcess(app))
    }

    @Test
    fun `every android process is either a user or a system process`() {
        val candidates = listOf(
            process("com.example.app", user = "u0_a123"),
            runtimeProcess("system_server", "system"),
            runtimeProcess("com.android.systemui", "system")
        )
        for (candidate in candidates) {
            assertTrue(
                "expected ${candidate.name} to be classified",
                ProcessFilter.isUserProcess(candidate) || ProcessFilter.isSystemProcess(candidate)
            )
        }
    }

    @Test
    fun `an app process is not also an other process`() {
        val app = process("com.example.app", user = "u0_a123")
        assertTrue(ProcessFilter.isAndroidProcess(app))
        assertFalse(ProcessFilter.isOtherProcess(app))
    }
}
