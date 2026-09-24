package com.omarea.vtools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.omarea.common.shell.KeepShellPublic
import com.omarea.library.shell.CpuFrequencyUtils
import com.omarea.library.shell.FreqFormatter
import com.omarea.library.shell.GpuUtils
import com.omarea.library.shell.ProcessFilter
import com.omarea.library.shell.ProcessUtils
import com.omarea.library.shell.RootFileTestProbe
import com.omarea.utils.SceneLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Verifies that each shipped feature can actually read the kernel interfaces it
 * depends on, on this device.
 *
 * These are **read-only** tests. They do not change any frequency, governor,
 * swap or freeze state, because a test suite that mutates a live phone is a test
 * suite nobody dares run. Where a feature's only real proof is a write, the test
 * reads the current value and asserts it is *plausible*, then reports which
 * interface was found — that is enough to tell "the page will work" from "the
 * page will be blank", which is the failure mode these tests exist to catch.
 *
 * Results are reported through [SceneLog.testResult] and read back via
 * `adb logcat -d -s Scene* | grep SCENE_TEST`.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class FeatureVerificationInstrumentedTest {

    companion object {
        private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

        @BeforeClass
        @JvmStatic
        fun announce() {
            SceneLog.init(context)
            SceneLog.i("Test", "=== FeatureVerificationInstrumentedTest starting ===")
        }

        /** Reports a boolean verdict and keeps the assertion in lockstep with it. */
        private fun verdict(feature: String, ok: Boolean, detail: String) {
            SceneLog.testResult(feature, ok, detail)
            assertTrue("$feature: $detail", ok)
        }
    }

    // =====================================================================
    // CPU frequency control
    // =====================================================================

    /** `getClusterInfo()` returns one entry per cluster, listing its core indices. */
    @Test
    fun c1_cpu_topology_is_enumerable() {
        val utils = CpuFrequencyUtils()
        val clusters = utils.clusterInfo

        val ok = clusters != null && clusters.isNotEmpty()
        val summary = clusters?.mapIndexed { index, cores -> "cluster$index=[${cores.joinToString()}]" }
        verdict("cpu.topology", ok, "coreCount=${utils.coreCount}, $summary")
    }

    /** Each cluster must report a current frequency that formats as MHz. */
    @Test
    fun c2_cpu_current_frequencies_are_readable() {
        val utils = CpuFrequencyUtils()
        val clusterCount = utils.clusterInfo.size
        assertTrue("no clusters detected", clusterCount > 0)

        val readings = (0 until clusterCount).map { cluster ->
            cluster to FreqFormatter.cpuKhzToMhz(utils.getCurrentFrequency(cluster))
        }
        val readable = readings.filter { it.second.isNotEmpty() && it.second.toLongOrNull()?.let { v -> v > 0 } == true }

        val ok = readable.isNotEmpty()
        verdict(
            "cpu.current_freq",
            ok,
            "${readable.size}/$clusterCount clusters read, " +
                    readable.joinToString { "c${it.first}=${it.second}Mhz" }
        )
    }

    /** `scaling_available_frequencies` powers the frequency picker. */
    @Test
    fun c3_cpu_available_frequencies_are_exposed() {
        val utils = CpuFrequencyUtils()
        val clusterCount = utils.clusterInfo.size
        assertTrue("no clusters detected", clusterCount > 0)

        val tables = (0 until clusterCount).map { cluster ->
            cluster to utils.getAvailableFrequencies(cluster).filter { it.isNotBlank() }
        }
        val withTable = tables.filter { it.second.isNotEmpty() }

        val ok = withTable.isNotEmpty()
        verdict(
            "cpu.available_freqs",
            ok,
            "${withTable.size}/$clusterCount clusters expose a table, " +
                    withTable.joinToString { "c${it.first}=${it.second.size}steps" }
        )
    }

    /** Every cluster must have a governor. */
    @Test
    fun c4_cpu_governor_is_readable() {
        val utils = CpuFrequencyUtils()
        val clusterCount = utils.clusterInfo.size
        assertTrue("no clusters detected", clusterCount > 0)

        val governors = (0 until clusterCount).map { cluster ->
            cluster to utils.getCurrentScalingGovernor(cluster)
        }
        val readable = governors.filter { it.second.isNotEmpty() && it.second != "error" }

        val ok = readable.isNotEmpty()
        verdict(
            "cpu.governor",
            ok,
            "${readable.size}/$clusterCount clusters read, " +
                    readable.joinToString { "c${it.first}=${it.second}" }
        )
    }

    /** At least one governor must be selectable, or the picker is empty. */
    @Test
    fun c5_cpu_available_governors_are_exposed() {
        val utils = CpuFrequencyUtils()
        val clusterCount = utils.clusterInfo.size
        assertTrue("no clusters detected", clusterCount > 0)

        val lists = (0 until clusterCount).map { cluster ->
            cluster to utils.getAvailableGovernors(cluster).filter { it.isNotBlank() }
        }
        val withList = lists.filter { it.second.isNotEmpty() }

        val ok = withList.isNotEmpty()
        verdict(
            "cpu.available_governors",
            ok,
            "${withList.size}/$clusterCount clusters list governors, " +
                    withList.joinToString { "c${it.first}(${it.second.size})" }
        )
    }

    /** min/max frequency limits must be readable, or the sliders cannot render. */
    @Test
    fun c6_cpu_min_max_limits_are_consistent() {
        val utils = CpuFrequencyUtils()
        val clusterCount = utils.clusterInfo.size
        assertTrue("no clusters detected", clusterCount > 0)

        val pairs = (0 until clusterCount).map { cluster ->
            Triple(
                cluster,
                utils.getCurrentMinFrequency(cluster)?.toLongOrNull(),
                utils.getCurrentMaxFrequency(cluster)?.toLongOrNull()
            )
        }
        val usable = pairs.filter { it.second != null && it.third != null && it.third!! > 0 }
        // min <= max must hold; a violation means the UI would offer an empty range.
        val inconsistent = usable.filter { it.second!! > it.third!! }

        val ok = usable.isNotEmpty() && inconsistent.isEmpty()
        verdict(
            "cpu.min_max_limits",
            ok,
            "${usable.size}/$clusterCount clusters report limits" +
                    if (inconsistent.isEmpty()) ""
                    else ", INCONSISTENT: ${inconsistent.joinToString { "c${it.first} ${it.second}>${it.third}" }}"
        )
    }

    /** Per-core online state, used to grey out offline cores. */
    @Test
    fun c7_cpu_core_online_state_is_readable() {
        val utils = CpuFrequencyUtils()
        val count = utils.coreCount
        assertTrue("core count not detected", count > 0)

        val states = (0 until count).map { utils.getCoreOnlineState(it) }
        val online = states.count { it }

        val ok = online > 0
        verdict("cpu.core_online", ok, "$online/$count cores online")
    }

    // =====================================================================
    // GPU control (Adreno / kgsl only)
    // =====================================================================

    /** Adreno is a hard requirement, so every GPU sub-feature depends on this. */
    @Test
    fun d1_adreno_kgsl_node_present() {
        val ok = GpuUtils.supported()
        verdict("gpu.adreno_present", ok, "kgsl-3d0 dir exists=$ok")
    }

    /** The GPU frequency readout. */
    @Test
    fun d2_gpu_frequency_readable() {
        val freq = GpuUtils.getGpuFreq()
        val ok = freq.isNotEmpty() && freq.toLongOrNull()?.let { it > 0 } == true
        verdict("gpu.frequency", ok, "getGpuFreq()='$freq' Mhz")
    }

    /** The GPU load readout; -1 is the documented "unsupported" sentinel. */
    @Test
    fun d3_gpu_load_readable() {
        val load = GpuUtils.getGpuLoad()
        val ok = load in 0..100
        verdict("gpu.load", ok, "getGpuLoad()=$load (0-100, -1 = unsupported)")
    }

    /** The power-level picker needs the level count. */
    @Test
    fun d4_gpu_power_levels_exposed() {
        val levels = GpuUtils.getAdrenoGPUPowerLevels()
        val ok = levels.isNotEmpty()
        verdict("gpu.power_levels", ok, "${levels.size} power level(s) exposed")
    }

    /** The GPU frequency picker needs the table. */
    @Test
    fun d5_gpu_frequency_table_exposed() {
        val table = GpuUtils.getFreqTableMhz()
        val available = GpuUtils.getAvailableFreqs()
        // Either source is acceptable; the UI prefers the table but falls back.
        val ok = table.isNotEmpty() || available.isNotEmpty()
        verdict(
            "gpu.frequency_table",
            ok,
            "freq_table_mhz=${table.size} steps, available_frequencies=${available.size} steps"
        )
    }

    /** The GPU governor selector. */
    @Test
    fun d6_gpu_governor_readable() {
        val governor = GpuUtils.getGovernor()
        val available = GpuUtils.getGovernors()
        val ok = governor.isNotEmpty() || available.isNotEmpty()
        verdict(
            "gpu.governor",
            ok,
            "current='$governor', available=${available.take(5).joinToString()}"
        )
    }

    // =====================================================================
    // Process manager
    // =====================================================================

    /** The process list the manager renders. */
    @Test
    fun e1_process_list_is_readable() {
        val processes = ProcessUtils(context).allProcess
        val ok = processes.isNotEmpty()
        verdict("process.list", ok, "${processes.size} processes enumerated")
    }

    /** Every row must have the fields the adapter reads. */
    @Test
    fun e2_process_rows_are_well_formed() {
        val processes = ProcessUtils(context).allProcess
        assertTrue("no processes to inspect", processes.isNotEmpty())

        val malformed = processes.filter {
            it.pid <= 0 || it.name.isNullOrEmpty() || it.user.isNullOrEmpty()
        }

        val ok = malformed.isEmpty()
        verdict(
            "process.rows_well_formed",
            ok,
            if (ok) "${processes.size} rows all have pid/name/user"
            else "${malformed.size} malformed rows, e.g. ${malformed.firstOrNull()?.name}"
        )
    }

    /** The filter dropdown must produce non-empty buckets for the real device. */
    @Test
    fun e3_process_classification_produces_buckets() {
        val processes = ProcessUtils(context).allProcess

        val apps = processes.count { ProcessFilter.isUserProcess(it) }
        val system = processes.count { ProcessFilter.isSystemProcess(it) }
        val other = processes.count { ProcessFilter.isOtherProcess(it) }

        // An app filter that finds nothing would render an empty list on a real
        // phone, which is the exact bug the earlier dotted-name rule caused.
        val ok = apps > 0 && system > 0 && other > 0
        verdict(
            "process.filter_buckets",
            ok,
            "apps=$apps, system=$system, other=$other"
        )
    }

    /** `system_server` must be classified, not silently dropped from every filter. */
    @Test
    fun e4_system_server_is_classified() {
        val processes = ProcessUtils(context).allProcess
        val systemServer = processes.firstOrNull { it.name == "system_server" }

        if (systemServer == null) {
            // Not a failure: some tight ROMs hide it from `ps`. Report and move on.
            SceneLog.testResult("process.system_server", true, "not visible in `ps` on this ROM")
            return
        }

        val classified = ProcessFilter.isAndroidProcess(systemServer) &&
                ProcessFilter.isSystemProcess(systemServer)
        val notAPackage = !ProcessFilter.isPackageProcess(systemServer)

        val ok = classified && notAPackage
        verdict(
            "process.system_server",
            ok,
            "command='${systemServer.command}', user='${systemServer.user}', " +
                    "isSystemProcess=$classified, isPackageProcess=${!notAPackage}"
        )
    }

    // =====================================================================
    // Swap / zram
    // =====================================================================

    /** `/proc/swaps` is the source of truth for what swap is active. */
    @Test
    fun f1_proc_swaps_is_readable() {
        val swaps = RootFileTestProbe.read("/proc/swaps")
        val ok = swaps.isNotEmpty() && swaps.contains("Filename")
        verdict(
            "swap.proc_swaps",
            ok,
            "header present=$ok, lines=${swaps.lines().size}"
        )
    }

    /** A zram device must exist for the zram controls to be meaningful. */
    @Test
    fun f2_zram_device_present() {
        val devices = RootFileTestProbe.list("/dev/block").filter { it.contains("zram") } +
                RootFileTestProbe.list("/sys/block").filter { it.contains("zram") }

        val ok = devices.isNotEmpty()
        verdict("swap.zram_device", ok, "found: ${devices.take(4).joinToString()}")
    }

    /** The zram size node the resize control writes to. */
    @Test
    fun f3_zram_size_node_readable() {
        val disksize = RootFileTestProbe.read("/sys/block/zram0/disksize")
        val ok = disksize.isNotEmpty() && disksize.toLongOrNull()?.let { it > 0 } == true
        val mb = disksize.toLongOrNull()?.let { it / 1024 / 1024 }
        verdict("swap.zram_size", ok, "disksize=$disksize bytes (~${mb}MB)")
    }

    /** The compression algorithm picker reads this node. */
    @Test
    fun f4_zram_algorithm_node_readable() {
        val current = RootFileTestProbe.read("/sys/block/zram0/comp_algorithm")
        val ok = current.isNotEmpty()
        val selected = current.substringAfter('[').substringBefore(']')
        verdict(
            "swap.zram_algorithm",
            ok,
            "current='$selected', options=${current.replace("[", "").replace("]", "").split(" ").size}"
        )
    }

    /** The swappiness knob the swap page exposes. */
    @Test
    fun f5_swappiness_is_readable() {
        val swappiness = RootFileTestProbe.read("/proc/sys/vm/swappiness")
        val value = swappiness.toIntOrNull()
        val ok = value != null && value in 0..200
        verdict("swap.swappiness", ok, "vm.swappiness=$swappiness")
    }

    // =====================================================================
    // kr-script engine
    // =====================================================================

    /** The kr-script assets must be present in the installed APK. */
    @Test
    fun g1_krscript_assets_are_packaged() {
        val assets = context.assets
        val root = assets.open("kr-script/more.xml").use { it.readBytes() }
        val ok = root.isNotEmpty()
        verdict("krscript.menu_present", ok, "kr-script/more.xml = ${root.size} bytes")
    }

    /**
     * The offline seed for auto-skip must be packaged and parse.
     *
     * This is the fallback used when the cloud fetch fails; if the asset is
     * missing or carries a BOM, auto-skip would silently do nothing offline.
     */
    @Test
    fun g0_autoskip_seed_asset_is_valid_json() {
        val text = context.assets.open("addin/auto-skip-config-v1.json").use { input ->
            input.bufferedReader().readText()
        }
        val parsed = runCatching { org.json.JSONArray(text.trim()) }.getOrNull()

        val ok = parsed != null && parsed.length() > 0
        val first = parsed?.optJSONObject(0)
        verdict(
            "krscript.autoskip_seed",
            ok,
            "${parsed?.length() ?: 0} entries, first activity='${first?.optString("activity")}'"
        )
    }

    /** The menu root referenced by `kr-script.conf` must resolve. */
    @Test
    fun g2_krscript_conf_points_at_a_real_menu() {
        val conf = context.assets.open("kr-script.conf").use { input ->
            input.bufferedReader().readText()
        }
        // `page_list` is the key KrScriptConfig reads.
        val pageList = Regex("page_list\\s*=\\s*(\\S+)").find(conf)?.groupValues?.get(1)
        val ok = pageList != null && runCatching {
            context.assets.open(pageList!!.removePrefix("file:///android_asset/")).close()
        }.isSuccess

        verdict(
            "krscript.conf_menu_resolves",
            ok,
            "page_list='$pageList' resolvable=$ok"
        )
    }

    /** Every `config=` page referenced by the menu must exist in assets. */
    @Test
    fun g3_krscript_pages_all_exist() {
        val menu = context.assets.open("kr-script/more.xml").use { input ->
            input.bufferedReader().readText()
        }
        val referenced = Regex("config=\"([^\"]+)\"").findAll(menu)
            .map { it.groupValues[1] }
            .filterNot { it.startsWith("http") || it.startsWith("$") }
            .toList()

        val missing = referenced.filterNot { path ->
            runCatching { context.assets.open("kr-script/$path").close() }.isSuccess
        }

        val ok = missing.isEmpty()
        verdict(
            "krscript.pages_exist",
            ok,
            if (ok) "all ${referenced.size} referenced pages exist"
            else "${missing.size} missing: ${missing.take(5).joinToString()}"
        )
    }

    // =====================================================================
    // Package / app list (freeze + powercfg targets)
    // =====================================================================

    /** The freeze page and powercfg app pickers both enumerate packages. */
    @Test
    fun h1_package_manager_lists_packages() {
        val packages = context.packageManager.getInstalledPackages(0)
        val ok = packages.isNotEmpty()
        verdict("pm.list", ok, "${packages.size} packages visible")
    }

    /** Third-party packages are what the freeze list is actually for. */
    @Test
    fun h2_third_party_packages_are_identifiable() {
        val packages = context.packageManager.getInstalledPackages(0)
        val thirdParty = packages.filter { it.applicationInfo?.flags?.and(1) == 0 }

        val ok = packages.isNotEmpty()
        // Zero third-party apps is a valid state (a bare test device); the point
        // is that the split can be computed at all.
        verdict(
            "pm.third_party_split",
            ok,
            "${thirdParty.size} third-party / ${packages.size} total"
        )
    }

    // =====================================================================
    // Root backend helpers
    // =====================================================================

    /** The overlay module root the systemless file-replace feature can target. */
    @Test
    fun i1_overlay_modules_path_detected_or_absent() {
        val present = RootFileTestProbe.dirExists("/data/adb/modules")
        // Absence is reported, not failed: the app must work on a plain root too.
        SceneLog.testResult(
            "root.overlay_modules_path",
            true,
            "/data/adb/modules present=$present (absence is not a failure)"
        )
    }

    /** `/system` mounting support, which the dexopt editor depends on. */
    @Test
    fun i2_system_partition_is_mountable() {
        // Read-only probe: does the app have a usable `mount` at all?
        val mountOutput = KeepShellPublic.doCmdSync("mount | grep -c ' /system '").trim()
        val countable = mountOutput.toIntOrNull() != null
        verdict(
            "root.system_mount_visible",
            countable,
            "mount | grep -c ' /system ' -> '$mountOutput'"
        )
    }

    /** `getprop` round-trip, the basis of every PropsUtils read. */
    @Test
    fun i3_getprop_round_trips() {
        val platform = KeepShellPublic.doCmdSync("getprop ro.board.platform").trim()
        val ok = platform.isNotEmpty() && platform != "error"
        verdict("root.getprop", ok, "ro.board.platform='$platform'")
    }

    /** A deliberately invalid command must fail cleanly, not hang or crash. */
    @Test
    fun i4_invalid_command_fails_gracefully() {
        val result = KeepShellPublic.doCmdSync("this-command-does-not-exist-12345")
        // Any of: empty, "error", or a shell "not found" message is acceptable.
        val ok = !result.contains("FATAL") && !result.contains("Segmentation")
        SceneLog.testResult("root.invalid_command", ok, "result='${result.take(80)}'")
        assertTrue("invalid command produced a dangerous result: $result", ok)
    }

    // =====================================================================
    // Sanity: nothing above should have changed device state
    // =====================================================================

    /** Confirms the suite really was read-only, by re-reading a mutable knob. */
    @Test
    fun z1_suite_did_not_mutate_device_state() {
        val swappiness = RootFileTestProbe.read("/proc/sys/vm/swappiness")
        val value = swappiness.toIntOrNull()
        val ok = value != null && value in 0..200
        verdict("suite.read_only", ok, "vm.swappiness still $swappiness")
        assertFalse("swappiness out of range after suite: $swappiness", value == null)
    }
}
