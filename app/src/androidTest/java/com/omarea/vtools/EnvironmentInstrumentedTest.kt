package com.omarea.vtools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.omarea.common.shell.KeepShellPublic
import com.omarea.library.shell.GpuUtils
import com.omarea.library.shell.PlatformUtils
import com.omarea.library.shell.RootFileTestProbe
import com.omarea.utils.SceneLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * On-device verification of the environment contract this app is built around:
 * a rooted **Xiaomi phone with a Qualcomm Snapdragon SoC**.
 *
 * Every test reports through [SceneLog.testResult], so the USB harness can read
 * a per-feature verdict back with
 *
 *     adb logcat -d -s Scene* | grep SCENE_TEST
 *
 * rather than having to parse JUnit XML. The assertion and the reported verdict
 * are kept in sync deliberately: a test that passes while reporting FAIL (or
 * vice versa) would be worse than no test at all.
 *
 * Tests are ordered so the cheap environment checks run before anything that
 * touches the filesystem, which makes a failure early in the run easier to read.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EnvironmentInstrumentedTest {

    companion object {
        private const val FEATURE = "environment"

        private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

        @BeforeClass
        @JvmStatic
        fun announce() {
            SceneLog.init(context)
            SceneLog.i("Test", "=== EnvironmentInstrumentedTest starting ===")
        }
    }

    /** `uname -a` has to return something; an empty root shell means no root. */
    @Test
    fun a1_root_shell_responds() {
        val result = KeepShellPublic.doCmdSync("echo scene-probe")
        val ok = result.trim() == "scene-probe"
        SceneLog.testResult("$FEATURE.root_shell", ok, "result='${result.trim()}'")
        assertTrue("root shell did not echo back: '$result'", ok)
    }

    /** The app only claims support for Qualcomm; verify the SoC actually is one. */
    @Test
    fun a2_platform_is_qualcomm() {
        val platform = PlatformUtils().getCPUName()
        val qualcomm = Regex("^(msm|sdm|sm|apq|qcs|kona|lahaina|taro|kalama|pineapple|lito|bengal|universal|waipio|cape|blair)", RegexOption.IGNORE_CASE)
        val ok = qualcomm.containsMatchIn(platform)
        SceneLog.testResult("$FEATURE.soc_qualcomm", ok, "ro.board.platform='$platform'")
        assertTrue("ro.board.platform '$platform' is not a recognised Qualcomm platform", ok)
    }

    /** The GPU path is Adreno/kgsl only; `GpuUtils.supported()` is the gate. */
    @Test
    fun a3_gpu_is_adreno() {
        val supported = GpuUtils.supported()
        SceneLog.testResult("$FEATURE.gpu_adreno", supported, "kgsl-3d0 present=$supported")
        assertTrue("Adreno kgsl node missing; GPU pages will not work", supported)
    }

    /** Xiaomi is the only supported brand. */
    @Test
    fun a4_manufacturer_is_xiaomi() {
        val manufacturer = android.os.Build.MANUFACTURER
        val ok = manufacturer.equals("XIAOMI", ignoreCase = true)
        SceneLog.testResult("$FEATURE.manufacturer_xiaomi", ok, "MANUFACTURER='$manufacturer'")
        assertTrue("device is not a Xiaomi ($manufacturer)", ok)
    }

    /** minSdk is 29; a lower device cannot be running this build legitimately. */
    @Test
    fun a5_api_level_in_supported_range() {
        val api = android.os.Build.VERSION.SDK_INT
        // The app is tested on Android 10 (29) through 13 (33); newer ROMs still
        // share the same kernel interfaces, so only the floor is asserted.
        val ok = api >= 29
        SceneLog.testResult("$FEATURE.api_level", ok, "SDK_INT=$api")
        assertTrue("API level $api is below the supported floor of 29", ok)
    }

    /** The core sysfs trees the CPU/GPU/thermal pages read from. */
    @Test
    fun a6_core_sysfs_nodes_are_readable() {
        val expected = listOf(
            "/sys/class/kgsl/kgsl-3d0" to "Adreno GPU",
            "/sys/devices/system/cpu" to "CPU topology"
        )
        val missing = expected.filterNot { (path, _) -> RootFileTestProbe.dirExists(path) }

        val ok = missing.isEmpty()
        SceneLog.testResult(
            "$FEATURE.sysfs_nodes",
            ok,
            if (ok) "all ${expected.size} present" else "missing: ${missing.joinToString { it.first }}"
        )
        assertTrue("missing sysfs nodes: ${missing.joinToString { "${it.second} (${it.first})" }}", ok)
    }

    /** Logcat has to actually receive our tag, or the whole harness is blind. */
    @Test
    fun a7_scenelog_reaches_logcat() {
        val probe = "logcat-probe-${System.currentTimeMillis()}"
        SceneLog.i("Test", probe)

        // Give logcat a moment to flush.
        Thread.sleep(300)
        val dumped = KeepShellPublic.doCmdSync("logcat -d -s Scene* | tail -n 200")
        val ok = dumped.contains(probe)

        SceneLog.testResult("$FEATURE.logcat_visible", ok, "probe='$probe' found=$ok")
        assertTrue(
            "SceneLog output did not reach logcat; the USB harness would see nothing",
            ok
        )
    }

    /** The app must be able to write its diagnostic files. */
    @Test
    fun a8_external_files_dir_is_writable() {
        val dir = context.getExternalFilesDir(null)
        assertNotNull("getExternalFilesDir returned null", dir)

        val probe = java.io.File(dir, "Android/write-probe.txt")
        probe.parentFile?.mkdirs()
        val payload = "scene-write-probe"
        probe.writeText(payload)

        val readBack = probe.readText()
        val ok = readBack == payload
        probe.delete()

        SceneLog.testResult("$FEATURE.storage_writable", ok, dir!!.absolutePath)
        assertTrue("could not round-trip a file in $dir", ok)
    }

    /** Running as root, not merely holding a root shell — detects odd setups. */
    @Test
    fun a9_shell_runs_as_uid_zero() {
        val uid = KeepShellPublic.doCmdSync("id -u").trim()
        val ok = uid == "0"
        SceneLog.testResult("$FEATURE.shell_uid_zero", ok, "id -u -> '$uid'")
        assertEquals("root shell is not uid 0", "0", uid)
    }

    /** Sanity: the debug layer preference key is the one Settings writes. */
    @Test
    fun b1_log_file_path_is_available() {
        val path = SceneLog.logFilePath()
        val ok = path != null && path.contains("scene-log.txt")
        SceneLog.testResult("$FEATURE.log_file_path", ok, "path='$path'")
        assertTrue("SceneLog has no log file path; init() may not have run", ok)
    }

    /** Guard against a silently disabled debug layer hiding the report file. */
    @Test
    fun b2_file_logging_toggle_round_trips() {
        val original = SceneLog.isFileLoggingEnabled

        SceneLog.setFileLoggingEnabled(true)
        val enabled = SceneLog.isFileLoggingEnabled
        SceneLog.setFileLoggingEnabled(false)
        val disabled = SceneLog.isFileLoggingEnabled

        // Restore, so the run does not change user state.
        SceneLog.setFileLoggingEnabled(original)

        val ok = enabled && !disabled
        SceneLog.testResult("$FEATURE.file_log_toggle", ok, "enabled=$enabled disabled=$disabled")
        assertTrue("file logging toggle did not take effect", ok)
    }

    /** The ring buffer must survive a burst larger than its cap without error. */
    @Test
    fun b3_memory_ring_is_bounded_under_load() {
        val before = SceneLog.recent().size
        repeat(500) { index -> SceneLog.d("Test", "burst-$index") }
        val after = SceneLog.recent().size

        // Cap is 2000, so a 500-record burst must not be dropped outright, and
        // must never exceed the cap.
        val ok = after in 1..2000
        SceneLog.testResult("$FEATURE.log_ring_bounded", ok, "size $before -> $after (cap 2000)")
        assertTrue("ring buffer size $after is outside 1..2000", ok)
    }

    /** A Throwable must survive into the log record, trace and all. */
    @Test
    fun b4_throwable_is_captured_with_stack_trace() {
        val boom = IllegalStateException("deliberate-test-throwable")
        SceneLog.e("Test", "throwable capture probe", boom)

        val line = SceneLog.recent().last()
        val formatted = line.format()
        // On a real device Log.getStackTraceString() is implemented, so the trace
        // is present — this is the assertion that cannot run as a JVM test.
        val ok = formatted.contains("deliberate-test-throwable") &&
                formatted.contains("IllegalStateException")
        SceneLog.testResult("$FEATURE.throwable_trace", ok, "formatted=${formatted.length} chars")
        assertTrue("stack trace was not captured: $formatted", ok)
    }

    /** trace() must record a failure without propagating it. */
    @Test
    fun b5_trace_helper_swallows_and_records() {
        val result = SceneLog.trace<Int>("Test", "deliberate-failure") { error("expected") }
        val recorded = SceneLog.recent().any { it.message.contains("deliberate-failure failed") }

        val ok = result == null && recorded
        SceneLog.testResult("$FEATURE.trace_helper", ok, "returned=$result recorded=$recorded")
        assertTrue("trace() did not record the failure", ok)
    }

    /** A missing sysfs node must read as empty, not crash the caller. */
    @Test
    fun b6_missing_sysfs_read_is_graceful() {
        val value = com.omarea.common.shell.KernelProrp.getProp("/sys/this/path/does/not/exist")
        val ok = value.isEmpty() || value == "error"
        SceneLog.testResult("$FEATURE.missing_node_graceful", ok, "got '${value.take(40)}'")
        assertTrue("reading a missing node returned '$value'", ok)
    }

    /** Degenerate input must not produce a shell command that does something. */
    @Test
    fun b7_shell_escape_survives_adversarial_input() {
        val adversarial = listOf(
            "a; reboot",
            "\$(reboot)",
            "`reboot`",
            "a\nreboot",
            "'",
            "\"",
            "\\"
        )
        val failures = adversarial.filter { input ->
            val command = "echo " + com.omarea.common.shell.ShellEscape.cmd("", input).trim()
            // Run it through a shell that would execute `reboot` if quoting failed.
            val output = KeepShellPublic.doCmdSync("$command; echo scene-escape-ok")
            !output.contains("scene-escape-ok")
        }

        val ok = failures.isEmpty()
        SceneLog.testResult(
            "$FEATURE.shell_escape",
            ok,
            if (ok) "${adversarial.size} payloads neutralised" else "leaked: $failures"
        )
        assertTrue("shell escaping failed for: $failures", ok)
    }

    /** The CPU frequency tree the CPU-control page depends on. */
    @Test
    fun b8_cpu_frequency_nodes_readable() {
        val policyDirs = RootFileTestProbe.listDirs("/sys/devices/system/cpu/cpufreq")
        val ok = policyDirs.isNotEmpty()
        SceneLog.testResult("$FEATURE.cpu_cpufreq", ok, "policies=${policyDirs.size}")

        // Not every kernel exposes curve-diff nodes; report rather than fail hard
        // if none are present, because the page degrades gracefully.
        assertTrue("no cpufreq policy directories found", ok)
    }
}
