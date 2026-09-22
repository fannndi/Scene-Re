package com.omarea.vtools.kernel

import com.omarea.common.shell.KeepShellPublic

/**
 * CPU cluster model discovered from `/sys/devices/system/cpu/cpufreq/policy*`.
 *
 * Discovery is dynamic instead of hardcoding policy0/policy6 so the same screen works on any
 * Qualcomm platform: surya exposes policy0 (cpu0-5, little) and policy6 (cpu6-7, big).
 */
data class CpuCluster(
    val policy: String,
    val cpus: String,
    val governor: String,
    val availableGovernors: List<String>,
    val minFreqKHz: Long,
    val maxFreqKHz: Long,
    val hardwareMinFreqKHz: Long,
    val hardwareMaxFreqKHz: Long,
    val availableFrequenciesKHz: List<Long>
) {
    val index: Int
        get() = policy.removePrefix("policy").toIntOrNull() ?: 0
}

object CpuClusters {
    private const val CPUFREQ_DIR = "/sys/devices/system/cpu/cpufreq"

    /** Reads every cpufreq policy in one shell round trip. */
    fun load(): List<CpuCluster> {
        val script = buildString {
            append("for p in ").append(CPUFREQ_DIR).append("/policy*; do\n")
            append("  g=\$(cat \$p/scaling_governor 2>/dev/null)\n")
            append("  [ -n \"\$g\" ] || continue\n")
            // Only the directory name is kept; the screen rebuilds the full path when writing.
            append("  echo \"POLICY=\${p##*/}\"\n")
            append("  echo \"CPUS=\$(cat \$p/related_cpus 2>/dev/null)\"\n")
            append("  echo \"GOV=\$g\"\n")
            append("  echo \"AGOV=\$(cat \$p/scaling_available_governors 2>/dev/null)\"\n")
            append("  echo \"MIN=\$(cat \$p/scaling_min_freq 2>/dev/null)\"\n")
            append("  echo \"MAX=\$(cat \$p/scaling_max_freq 2>/dev/null)\"\n")
            append("  echo \"HMIN=\$(cat \$p/cpuinfo_min_freq 2>/dev/null)\"\n")
            append("  echo \"HMAX=\$(cat \$p/cpuinfo_max_freq 2>/dev/null)\"\n")
            append("  echo \"AFREQ=\$(cat \$p/scaling_available_frequencies 2>/dev/null)\"\n")
            append("done\n")
        }
        val output = try {
            KeepShellPublic.doCmdSync(script)
        } catch (ex: Exception) {
            ""
        }
        return parse(output)
    }

    /** Visible for unit tests; parses the marker-based output of [load]. */
    internal fun parse(output: String): List<CpuCluster> {
        val clusters = ArrayList<CpuCluster>()
        var policy: String? = null
        var cpus = ""
        var governor = ""
        var availableGovernors = emptyList<String>()
        var minFreq = 0L
        var maxFreq = 0L
        var hardwareMinFreq = 0L
        var hardwareMaxFreq = 0L
        var frequencies = emptyList<Long>()

        fun flush() {
            val currentPolicy = policy ?: return
            clusters.add(
                CpuCluster(
                    policy = currentPolicy,
                    cpus = cpus,
                    governor = governor,
                    availableGovernors = availableGovernors,
                    minFreqKHz = minFreq,
                    maxFreqKHz = maxFreq,
                    hardwareMinFreqKHz = hardwareMinFreq,
                    hardwareMaxFreqKHz = hardwareMaxFreq,
                    availableFrequenciesKHz = frequencies
                )
            )
        }

        for (line in output.lines()) {
            val separator = line.indexOf('=')
            if (separator <= 0) {
                continue
            }
            val key = line.substring(0, separator)
            val value = line.substring(separator + 1).trim()
            when (key) {
                "POLICY" -> {
                    flush()
                    policy = value
                    cpus = ""
                    governor = ""
                    availableGovernors = emptyList()
                    minFreq = 0L
                    maxFreq = 0L
                    hardwareMinFreq = 0L
                    hardwareMaxFreq = 0L
                    frequencies = emptyList()
                }

                "CPUS" -> cpus = value
                "GOV" -> governor = value
                "AGOV" -> availableGovernors = value.split(Regex("\\s+")).filter { it.isNotEmpty() }
                "MIN" -> minFreq = value.toLongOrNull() ?: 0L
                "MAX" -> maxFreq = value.toLongOrNull() ?: 0L
                "HMIN" -> hardwareMinFreq = value.toLongOrNull() ?: 0L
                "HMAX" -> hardwareMaxFreq = value.toLongOrNull() ?: 0L
                "AFREQ" -> frequencies = value.split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }.sorted()
            }
        }
        flush()
        return clusters.sortedBy { it.index }
    }

    fun setGovernor(cluster: CpuCluster, governor: String): Boolean {
        if (!isSafePolicy(cluster.policy) || !KernelShell.isSafeTextValue(governor)) {
            return false
        }
        return KernelShell.write("${CPUFREQ_DIR}/${cluster.policy}/scaling_governor", governor)
    }

    fun setMinFreq(cluster: CpuCluster, frequencyKHz: Long): Boolean {
        if (!isSafePolicy(cluster.policy) || frequencyKHz <= 0L) {
            return false
        }
        return KernelShell.write("${CPUFREQ_DIR}/${cluster.policy}/scaling_min_freq", frequencyKHz.toString())
    }

    fun setMaxFreq(cluster: CpuCluster, frequencyKHz: Long): Boolean {
        if (!isSafePolicy(cluster.policy) || frequencyKHz <= 0L) {
            return false
        }
        return KernelShell.write("${CPUFREQ_DIR}/${cluster.policy}/scaling_max_freq", frequencyKHz.toString())
    }

    fun isSafePolicy(policy: String): Boolean = Regex("^policy[0-9]{1,2}$").matches(policy)
}

/** Frequency node exposed by the Adreno kgsl driver, in Hz or MHz depending on the kernel. */
data class GpuFrequencyControl(
    val path: String,
    val inMhz: Boolean,
    val current: Long,
    val available: List<Long>
)

/**
 * Adreno GPU state.
 *
 * Every field is nullable/empty when the corresponding node is absent or denied: on a Shizuku
 * (shell uid) session `/sys/class/kgsl` is refused on surya, so the section renders as unavailable
 * instead of failing.
 */
data class GpuInfo(
    val available: Boolean,
    val governor: String,
    val availableGovernors: List<String>,
    val minFreq: GpuFrequencyControl?,
    val maxFreq: GpuFrequencyControl?,
    val minPwrLevel: String,
    val maxPwrLevel: String,
    val defaultPwrLevel: String,
    val powerLevels: List<String>,
    val adrenoBoost: String,
    val throttling: String,
    val currentFreqHz: Long,
    val busyPercent: String,
    val temperature: String
)

object Gpu {
    private const val GPU_DIR = "/sys/class/kgsl/kgsl-3d0"
    private const val DEV_GOVERNOR = "$GPU_DIR/devfreq/governor"
    private const val DEV_AVAILABLE_GOVERNORS = "$GPU_DIR/devfreq/available_governors"
    private const val DEV_MIN_FREQ = "$GPU_DIR/devfreq/min_freq"
    private const val DEV_MAX_FREQ = "$GPU_DIR/devfreq/max_freq"
    private const val DEV_AVAILABLE_FREQUENCIES = "$GPU_DIR/devfreq/available_frequencies"
    private const val MIN_CLOCK_MHZ = "$GPU_DIR/min_clock_mhz"
    private const val MAX_CLOCK_MHZ = "$GPU_DIR/max_clock_mhz"
    private const val AVAILABLE_FREQUENCIES = "$GPU_DIR/available_frequencies"
    private const val FREQ_TABLE_MHZ = "$GPU_DIR/freq_table_mhz"
    private const val GPU_CLOCK = "$GPU_DIR/gpuclk"
    private const val MIN_PWRLEVEL = "$GPU_DIR/min_pwrlevel"
    private const val MAX_PWRLEVEL = "$GPU_DIR/max_pwrlevel"
    private const val DEFAULT_PWRLEVEL = "$GPU_DIR/default_pwrlevel"
    private const val NUM_PWRLEVELS = "$GPU_DIR/num_pwrlevels"
    private const val ADRENO_BOOST = "$GPU_DIR/devfreq/adrenoboost"
    private const val THROTTLING = "$GPU_DIR/throttling"
    private const val GPU_BUSY = "$GPU_DIR/gpu_busy_percentage"
    private const val GPU_TEMP = "$GPU_DIR/temp"

    private val PATHS = listOf(
        DEV_GOVERNOR, DEV_AVAILABLE_GOVERNORS, DEV_MIN_FREQ, DEV_MAX_FREQ,
        DEV_AVAILABLE_FREQUENCIES, MIN_CLOCK_MHZ, MAX_CLOCK_MHZ,
        AVAILABLE_FREQUENCIES, FREQ_TABLE_MHZ, GPU_CLOCK,
        MIN_PWRLEVEL, MAX_PWRLEVEL, DEFAULT_PWRLEVEL, NUM_PWRLEVELS,
        ADRENO_BOOST, THROTTLING, GPU_BUSY, GPU_TEMP
    )

    fun load(): GpuInfo {
        val values = KernelShell.readMany(PATHS)

        val governor = values[DEV_GOVERNOR].orEmpty()
        val availableGovernors = values[DEV_AVAILABLE_GOVERNORS].orEmpty()
            .split(Regex("\\s+")).filter { it.isNotEmpty() }
        val availableFrequenciesHz = parseFrequencies(values[DEV_AVAILABLE_FREQUENCIES])
            .ifEmpty { parseFrequencies(values[AVAILABLE_FREQUENCIES]) }
        val freqTableMhz = parseFrequencies(values[FREQ_TABLE_MHZ])
        val powerLevels = powerLevels(values[NUM_PWRLEVELS].orEmpty())
        val minPwrLevel = values[MIN_PWRLEVEL].orEmpty()
        val maxPwrLevel = values[MAX_PWRLEVEL].orEmpty()
        val defaultPwrLevel = values[DEFAULT_PWRLEVEL].orEmpty()
        val adrenoBoost = values[ADRENO_BOOST].orEmpty()
        val throttling = values[THROTTLING].orEmpty()
        val currentFreqHz = values[GPU_CLOCK].orEmpty().toLongOrNull() ?: 0L

        val available = governor.isNotEmpty() || availableGovernors.isNotEmpty() ||
                minPwrLevel.isNotEmpty() || maxPwrLevel.isNotEmpty() ||
                values[DEV_MIN_FREQ].orEmpty().isNotEmpty() || values[MIN_CLOCK_MHZ].orEmpty().isNotEmpty()

        val minFreq = buildControl(
            hzPath = DEV_MIN_FREQ,
            hzValue = values[DEV_MIN_FREQ].orEmpty(),
            mhzPath = MIN_CLOCK_MHZ,
            mhzValue = values[MIN_CLOCK_MHZ].orEmpty(),
            availableFrequenciesHz = availableFrequenciesHz,
            freqTableMhz = freqTableMhz
        )
        val maxFreq = buildControl(
            hzPath = DEV_MAX_FREQ,
            hzValue = values[DEV_MAX_FREQ].orEmpty(),
            mhzPath = MAX_CLOCK_MHZ,
            mhzValue = values[MAX_CLOCK_MHZ].orEmpty(),
            availableFrequenciesHz = availableFrequenciesHz,
            freqTableMhz = freqTableMhz
        )

        return GpuInfo(
            available = available,
            governor = governor,
            availableGovernors = availableGovernors,
            minFreq = minFreq,
            maxFreq = maxFreq,
            minPwrLevel = minPwrLevel,
            maxPwrLevel = maxPwrLevel,
            defaultPwrLevel = defaultPwrLevel,
            powerLevels = powerLevels,
            adrenoBoost = adrenoBoost,
            throttling = throttling,
            currentFreqHz = currentFreqHz,
            busyPercent = values[GPU_BUSY].orEmpty(),
            temperature = values[GPU_TEMP].orEmpty()
        )
    }

    private fun buildControl(
        hzPath: String,
        hzValue: String,
        mhzPath: String,
        mhzValue: String,
        availableFrequenciesHz: List<Long>,
        freqTableMhz: List<Long>
    ): GpuFrequencyControl? {
        if (hzValue.isNotEmpty()) {
            val current = hzValue.toLongOrNull() ?: return null
            return GpuFrequencyControl(
                path = hzPath,
                inMhz = false,
                current = current,
                available = availableFrequenciesHz
            )
        }
        if (mhzValue.isNotEmpty()) {
            val current = mhzValue.toLongOrNull() ?: return null
            val available = freqTableMhz.ifEmpty { availableFrequenciesHz.map { it / 1_000_000 } }.sorted()
            return GpuFrequencyControl(
                path = mhzPath,
                inMhz = true,
                current = current,
                available = available
            )
        }
        return null
    }

    private fun parseFrequencies(value: String?): List<Long> {
        return value.orEmpty().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }.filter { it > 0L }.sorted()
    }

    private fun powerLevels(value: String): List<String> {
        val count = value.toIntOrNull() ?: return emptyList()
        if (count <= 0 || count > 64) {
            return emptyList()
        }
        return (0 until count).map { it.toString() }
    }

    fun setGovernor(governor: String): Boolean {
        if (!KernelShell.isSafeTextValue(governor)) {
            return false
        }
        return KernelShell.write(DEV_GOVERNOR, governor)
    }

    fun setFrequency(control: GpuFrequencyControl, value: Long): Boolean {
        if (value <= 0L) {
            return false
        }
        return KernelShell.write(control.path, value.toString())
    }

    fun setPwrLevel(path: String, value: String): Boolean {
        if (path != MIN_PWRLEVEL && path != MAX_PWRLEVEL && path != DEFAULT_PWRLEVEL) {
            return false
        }
        if (!KernelShell.isNumeric(value)) {
            return false
        }
        return KernelShell.write(path, value)
    }

    fun setAdrenoBoost(value: String): Boolean {
        if (!KernelShell.isNumeric(value)) {
            return false
        }
        return KernelShell.write(ADRENO_BOOST, value)
    }

    fun setThrottling(enabled: Boolean): Boolean {
        return KernelShell.write(THROTTLING, if (enabled) "1" else "0")
    }

    val minPwrLevelPath: String get() = MIN_PWRLEVEL
    val maxPwrLevelPath: String get() = MAX_PWRLEVEL
    val defaultPwrLevelPath: String get() = DEFAULT_PWRLEVEL
    val adrenoBoostPath: String get() = ADRENO_BOOST
    val throttlingPath: String get() = THROTTLING
    val governorPath: String get() = DEV_GOVERNOR
}
