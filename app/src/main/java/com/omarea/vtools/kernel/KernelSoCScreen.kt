package com.omarea.vtools.kernel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.omarea.vtools.R
import com.omarea.vtools.ui.components.SceneEmptyState
import com.omarea.vtools.ui.components.SceneSectionCard
import com.omarea.vtools.ui.components.SceneTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SoC tab: per-cluster CPU frequency control and Adreno GPU control.
 *
 * Clusters and nodes are discovered at runtime; anything the current tier or ROM does not expose
 * renders as unavailable instead of crashing. Writes are disabled without root, and every write is
 * verified by the shell helper (read-back) before the UI reports success.
 */
@Composable
internal fun KernelSoCScreen(refreshKey: Int, hasRoot: Boolean, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var cpuClusters by remember { mutableStateOf<List<CpuCluster>>(emptyList()) }
    var gpu by remember { mutableStateOf<GpuInfo?>(null) }
    var thermalZones by remember { mutableStateOf<List<ThermalZone>>(emptyList()) }
    var reloadTrigger by remember { mutableIntStateOf(0) }

    var governorCluster by remember { mutableStateOf<CpuCluster?>(null) }
    var minFreqCluster by remember { mutableStateOf<CpuCluster?>(null) }
    var maxFreqCluster by remember { mutableStateOf<CpuCluster?>(null) }
    var gpuGovernorDialog by remember { mutableStateOf(false) }
    var gpuMinFreqDialog by remember { mutableStateOf<GpuFrequencyControl?>(null) }
    var gpuMaxFreqDialog by remember { mutableStateOf<GpuFrequencyControl?>(null) }
    var gpuPwrLevelDialog by remember { mutableStateOf<Pair<String, String>?>(null) }

    val writeFailed = stringResource(R.string.kernel_write_failed)
    val minPwrLevelTitle = stringResource(R.string.kernel_gpu_min_pwrlevel)
    val maxPwrLevelTitle = stringResource(R.string.kernel_gpu_max_pwrlevel)
    val defaultPwrLevelTitle = stringResource(R.string.kernel_gpu_default_pwrlevel)
    val gpuFrequencyUnitMhz = stringResource(R.string.kernel_unit_megahertz)

    LaunchedEffect(refreshKey, reloadTrigger) {
        loading = true
        withContext(Dispatchers.IO) {
            cpuClusters = CpuClusters.load()
            gpu = Gpu.load()
        }
        thermalZones = withContext(Dispatchers.IO) { ThermalZones.load() }
        loading = false
    }

    fun write(action: suspend () -> Boolean) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { action() }
            if (ok) {
                reloadTrigger++
            } else {
                onMessage(writeFailed)
            }
        }
    }

    KernelScreen {
        if (loading && cpuClusters.isEmpty() && gpu == null) {
            KernelLoading()
            return@KernelScreen
        }

        if (cpuClusters.isEmpty()) {
            SceneEmptyState(
                title = stringResource(R.string.kernel_cpu_unavailable),
                description = stringResource(R.string.kernel_cpu_unavailable_desc)
            )
        } else {
            cpuClusters.forEach { cluster ->
                SceneSectionCard(
                    title = clusterTitle(cluster.index),
                    iconRes = R.drawable.ic_menu_cpu,
                    accent = SceneTone.PRIMARY,
                    trailing = { if (!hasRoot) KernelRootRequiredChip() }
                ) {
                    KernelInfoRow(stringResource(R.string.kernel_cpu_cores), cluster.cpus)
                    KernelActionRow(
                        title = stringResource(R.string.kernel_cpu_governor),
                        value = cluster.governor,
                        enabled = hasRoot && cluster.availableGovernors.isNotEmpty()
                    ) { governorCluster = cluster }
                    KernelActionRow(
                        title = stringResource(R.string.kernel_cpu_min_freq),
                        value = formatCpuFrequency(cluster.minFreqKHz),
                        enabled = hasRoot && cluster.availableFrequenciesKHz.isNotEmpty()
                    ) { minFreqCluster = cluster }
                    KernelActionRow(
                        title = stringResource(R.string.kernel_cpu_max_freq),
                        value = formatCpuFrequency(cluster.maxFreqKHz),
                        enabled = hasRoot && cluster.availableFrequenciesKHz.isNotEmpty()
                    ) { maxFreqCluster = cluster }
                    if (cluster.hardwareMinFreqKHz > 0L || cluster.hardwareMaxFreqKHz > 0L) {
                        KernelInfoRow(
                            stringResource(R.string.kernel_cpu_hardware_range),
                            stringResource(
                                R.string.kernel_cpu_hardware_range_value,
                                formatCpuFrequency(cluster.hardwareMinFreqKHz),
                                formatCpuFrequency(cluster.hardwareMaxFreqKHz)
                            )
                        )
                    }
                }
            }
        }

        val gpuState = gpu
        if (gpuState == null || !gpuState.available) {
            SceneSectionCard(
                title = stringResource(R.string.kernel_section_gpu),
                iconRes = R.drawable.ic_menu_hot,
                accent = SceneTone.SECONDARY
            ) {
                SceneEmptyState(
                    title = stringResource(R.string.kernel_gpu_unavailable),
                    description = stringResource(R.string.kernel_gpu_unavailable_desc)
                )
            }
        } else {
            SceneSectionCard(
                title = stringResource(R.string.kernel_section_gpu),
                iconRes = R.drawable.ic_menu_hot,
                accent = SceneTone.SECONDARY,
                trailing = { if (!hasRoot) KernelRootRequiredChip() }
            ) {
                KernelActionRow(
                    title = stringResource(R.string.kernel_gpu_governor),
                    value = gpuState.governor,
                    enabled = hasRoot && gpuState.availableGovernors.isNotEmpty()
                ) { gpuGovernorDialog = true }

                gpuState.minFreq?.let { control ->
                    KernelActionRow(
                        title = stringResource(R.string.kernel_gpu_min_freq),
                        value = formatGpuFrequency(control, control.current),
                        enabled = hasRoot && control.available.isNotEmpty()
                    ) { gpuMinFreqDialog = control }
                }
                gpuState.maxFreq?.let { control ->
                    KernelActionRow(
                        title = stringResource(R.string.kernel_gpu_max_freq),
                        value = formatGpuFrequency(control, control.current),
                        enabled = hasRoot && control.available.isNotEmpty()
                    ) { gpuMaxFreqDialog = control }
                }

                if (gpuState.powerLevels.isNotEmpty()) {
                    KernelActionRow(
                        title = minPwrLevelTitle,
                        value = gpuState.minPwrLevel,
                        enabled = hasRoot && gpuState.minPwrLevel.isNotEmpty()
                    ) { gpuPwrLevelDialog = Gpu.minPwrLevelPath to minPwrLevelTitle }
                    KernelActionRow(
                        title = maxPwrLevelTitle,
                        value = gpuState.maxPwrLevel,
                        enabled = hasRoot && gpuState.maxPwrLevel.isNotEmpty()
                    ) { gpuPwrLevelDialog = Gpu.maxPwrLevelPath to maxPwrLevelTitle }
                    if (gpuState.defaultPwrLevel.isNotEmpty()) {
                        KernelActionRow(
                            title = defaultPwrLevelTitle,
                            value = gpuState.defaultPwrLevel,
                            enabled = hasRoot
                        ) { gpuPwrLevelDialog = Gpu.defaultPwrLevelPath to defaultPwrLevelTitle }
                    }
                }

                if (gpuState.throttling.isNotEmpty()) {
                    KernelSwitchRow(
                        title = stringResource(R.string.kernel_gpu_throttling),
                        checked = gpuState.throttling == "1",
                        enabled = hasRoot,
                        onCheckedChange = { enabled -> write { Gpu.setThrottling(enabled) } }
                    )
                }

                if (gpuState.currentFreqHz > 0L) {
                    KernelInfoRow(
                        stringResource(R.string.kernel_gpu_current_freq),
                        "${gpuState.currentFreqHz / 1_000_000} $gpuFrequencyUnitMhz"
                    )
                }
                KernelInfoRow(stringResource(R.string.kernel_gpu_load), gpuState.busyPercent)
                KernelInfoRow(
                    stringResource(R.string.kernel_gpu_temp),
                    formatTemperature(gpuState.temperature.toIntOrNull())
                )
            }
        }

        if (thermalZones.isNotEmpty()) {
            SceneSectionCard(
                title = stringResource(R.string.kernel_section_thermal),
                iconRes = R.drawable.ic_menu_hot
            ) {
                thermalZones.forEach { zone ->
                    KernelInfoRow(zone.type, formatTemperature(zone.temperatureTenths))
                }
            }
        }
    }

    governorCluster?.let { cluster ->
        KernelOptionDialog(
            title = stringResource(R.string.kernel_cpu_governor),
            options = cluster.availableGovernors,
            selected = cluster.governor,
            label = { it },
            onDismiss = { governorCluster = null },
            onSelect = { governor -> write { CpuClusters.setGovernor(cluster, governor) } }
        )
    }

    minFreqCluster?.let { cluster ->
        KernelOptionDialog(
            title = stringResource(R.string.kernel_cpu_min_freq),
            options = cluster.availableFrequenciesKHz,
            selected = cluster.minFreqKHz,
            label = { formatCpuFrequency(it) },
            onDismiss = { minFreqCluster = null },
            onSelect = { frequency -> write { CpuClusters.setMinFreq(cluster, frequency) } }
        )
    }

    maxFreqCluster?.let { cluster ->
        KernelOptionDialog(
            title = stringResource(R.string.kernel_cpu_max_freq),
            options = cluster.availableFrequenciesKHz,
            selected = cluster.maxFreqKHz,
            label = { formatCpuFrequency(it) },
            onDismiss = { maxFreqCluster = null },
            onSelect = { frequency -> write { CpuClusters.setMaxFreq(cluster, frequency) } }
        )
    }

    val gpuForDialogs = gpu
    if (gpuGovernorDialog && gpuForDialogs != null) {
        KernelOptionDialog(
            title = stringResource(R.string.kernel_gpu_governor),
            options = gpuForDialogs.availableGovernors,
            selected = gpuForDialogs.governor,
            label = { it },
            onDismiss = { gpuGovernorDialog = false },
            onSelect = { governor -> write { Gpu.setGovernor(governor) } }
        )
    }

    gpuMinFreqDialog?.let { control ->
        KernelOptionDialog(
            title = stringResource(R.string.kernel_gpu_min_freq),
            options = control.available,
            selected = control.current,
            label = { formatGpuFrequency(control, it) },
            onDismiss = { gpuMinFreqDialog = null },
            onSelect = { frequency -> write { Gpu.setFrequency(control, frequency) } }
        )
    }

    gpuMaxFreqDialog?.let { control ->
        KernelOptionDialog(
            title = stringResource(R.string.kernel_gpu_max_freq),
            options = control.available,
            selected = control.current,
            label = { formatGpuFrequency(control, it) },
            onDismiss = { gpuMaxFreqDialog = null },
            onSelect = { frequency -> write { Gpu.setFrequency(control, frequency) } }
        )
    }

    gpuPwrLevelDialog?.let { dialog ->
        val options = gpuForDialogs?.powerLevels.orEmpty()
        val selected = when (dialog.first) {
            Gpu.minPwrLevelPath -> gpuForDialogs?.minPwrLevel
            Gpu.maxPwrLevelPath -> gpuForDialogs?.maxPwrLevel
            else -> gpuForDialogs?.defaultPwrLevel
        }
        KernelOptionDialog(
            title = dialog.second,
            options = options,
            selected = selected,
            label = { it },
            onDismiss = { gpuPwrLevelDialog = null },
            onSelect = { level -> write { Gpu.setPwrLevel(dialog.first, level) } }
        )
    }
}

@Composable
private fun clusterTitle(index: Int): String {
    // surya has exactly two clusters: policy0 (cpu0-5, little) and policy6 (cpu6-7, big).
    return if (index == 0) {
        stringResource(R.string.kernel_cluster_little)
    } else {
        stringResource(R.string.kernel_cluster_big)
    }
}
