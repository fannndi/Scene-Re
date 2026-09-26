package com.omarea.utils

import com.omarea.common.shell.KeepShellPublic

/**
 * What the *running* kernel actually offers for CPU/GPU governors and I/O
 * schedulers, read once and cached.
 *
 * The profile scripts already select governors through ordered preference
 * chains and only ever write what `scaling_available_governors` advertises, but
 * that leaves the UI guessing: a kernel without `ondemand`/`conservative`
 * (the surya builds ship `schedutil`/`performance`/`powersave`/`userspace`)
 * would still show the full hardcoded candidate list. This object is the single
 * source of truth for the app: the dialogs list exactly what the kernel
 * advertises and lock the governor rows when the kernel exposes no choice at
 * all, and the resolved per-profile governor is shown instead of being assumed.
 *
 * Every read is guarded; an unreadable node yields an empty list and the UI
 * treats the feature as unavailable rather than falling back to hardcoded
 * names.
 */
object GovernorCapabilities {
    /** Preference chains, best first - kept in sync with the powercfg scripts. */
    val POWERSAVE_CHAIN = listOf("conservative", "schedutil", "powersave")
    val BALANCE_CHAIN = listOf("schedutil", "ondemand", "conservative")
    val PERFORMANCE_CHAIN = listOf("performance", "ondemand", "schedutil")
    val LIGHT_CHAIN = listOf("schedutil", "ondemand")
    val GPU_CHAIN = listOf("msm-adreno-tz", "msm-adreno-tz-v2", "simple_ondemand")

    private const val CACHE_MS = 300_000L

    data class Info(
        val cpu0: List<String> = emptyList(),
        val cpu6: List<String> = emptyList(),
        val gpu: List<String> = emptyList(),
        val io: List<String> = emptyList(),
        val currentCpu0: String = "",
        val currentCpu6: String = "",
        val currentGpu: String = ""
    ) {
        /** False when the kernel exposes no CPU governor list at all. */
        val cpuSelectable: Boolean get() = cpu0.size > 1 || cpu6.size > 1
        val gpuSelectable: Boolean get() = gpu.size > 1
        val ioSelectable: Boolean get() = io.size > 1
    }

    @Volatile
    private var cached: Info? = null

    @Volatile
    private var cachedAt = 0L

    /** First entry of [chain] the [available] list advertises, "" when none. */
    fun pick(available: List<String>, chain: List<String>): String =
        chain.firstOrNull { available.contains(it) } ?: ""

    /** Resolved governor for the scenario chains ("" = none advertised). */
    fun resolved(info: Info): Map<String, String> = linkedMapOf(
        "powersave" to pick(info.cpu0, POWERSAVE_CHAIN),
        "balance" to pick(info.cpu0, BALANCE_CHAIN),
        "performance" to pick(info.cpu0, PERFORMANCE_CHAIN),
        "light" to pick(info.cpu0, LIGHT_CHAIN),
        "gpu" to pick(info.gpu, GPU_CHAIN)
    )

    fun read(): Info {
        val hit = cached
        if (hit != null && System.currentTimeMillis() - cachedAt < CACHE_MS) {
            return hit
        }
        val values = try {
            parse(shell(PROBE))
        } catch (ex: Exception) {
            Info()
        }
        cached = values
        cachedAt = System.currentTimeMillis()
        return values
    }

    fun invalidate() {
        cached = null
    }

    @Volatile
    private var lastSynced = ""

    /**
     * Persist the resolved governor per scenario for the powercfg scripts, so
     * they never carry a hardcoded preference list either: they read
     * `/data/adb/scene/gov_chains.txt` (written here from the running kernel's
     * advertised lists) and fall back to their built-in defaults only when the
     * file is missing. A scenario resolving to no governor at all is written as
     * an empty value, which makes the profile skip the write and the dialog
     * lock the row.
     */
    fun syncChains(info: Info = read()) {
        try {
            val resolved = resolved(info)
            val body = StringBuilder()
            for (scenario in listOf("powersave", "balance", "performance", "light")) {
                body.append(scenario).append('=').append(resolved[scenario].orEmpty()).append('\n')
            }
            body.append("gpu=")
                .append(GPU_CHAIN.filter { info.gpu.contains(it) }.joinToString(" "))
                .append('\n')
            val content = body.toString()
            if (content == lastSynced) {
                return
            }
            lastSynced = content
            shell(
                "mkdir -p /data/adb/scene\n" +
                    "cat > /data/adb/scene/gov_chains.txt << 'SCENE_GOV_EOF'\n" +
                    content +
                    "SCENE_GOV_EOF"
            )
        } catch (ex: Exception) {
            lastSynced = ""
        }
    }

    /** Parse the marker-separated probe output; also usable from unit tests. */
    fun parse(output: String): Info {
        val fields = HashMap<String, String>()
        for (line in output.lineSequence()) {
            val index = line.indexOf('=')
            if (index > 0) {
                fields[line.substring(0, index).trim()] = line.substring(index + 1).trim()
            }
        }
        val io = fields["io"].orEmpty()
        return Info(
            cpu0 = splitList(fields["cpu0"]),
            cpu6 = splitList(fields["cpu6"]),
            gpu = splitList(fields["gpu"]),
            io = parseSchedulers(if (io.isNotEmpty()) io else fields["io2"].orEmpty()),
            currentCpu0 = fields["cur0"].orEmpty(),
            currentCpu6 = fields["cur6"].orEmpty(),
            currentGpu = fields["curgpu"].orEmpty()
        )
    }

    /** `[none] mq-deadline kyber` -> the scheduler names, no brackets. */
    fun parseSchedulers(output: String): List<String> {
        if (output.isEmpty()) {
            return emptyList()
        }
        return output.split(Regex("\\s+"))
            .map { it.trim().removePrefix("[").removeSuffix("]") }
            .filter { it.isNotEmpty() }
    }

    private fun splitList(value: String?): List<String> =
        value.orEmpty().split(Regex("\\s+")).filter { it.isNotEmpty() }

    private const val PROBE = """
cpu0=${'$'}(cat /sys/devices/system/cpu/cpufreq/policy0/scaling_available_governors 2> /dev/null)
cur0=${'$'}(cat /sys/devices/system/cpu/cpufreq/policy0/scaling_governor 2> /dev/null)
cpu6=${'$'}(cat /sys/devices/system/cpu/cpufreq/policy6/scaling_available_governors 2> /dev/null)
cur6=${'$'}(cat /sys/devices/system/cpu/cpufreq/policy6/scaling_governor 2> /dev/null)
gpu=${'$'}(cat /sys/class/kgsl/kgsl-3d0/devfreq/available_governors 2> /dev/null)
curgpu=${'$'}(cat /sys/class/kgsl/kgsl-3d0/devfreq/governor 2> /dev/null)
io=${'$'}(cat /sys/block/sda/queue/scheduler 2> /dev/null)
io2=${'$'}(cat /sys/block/mmcblk0/queue/scheduler 2> /dev/null)
"""

    private fun shell(command: String): String = KeepShellPublic.doCmdSync(command)
}
