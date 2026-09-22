package com.omarea.vtools.fragments

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.omarea.library.shell.KernelParameters
import com.omarea.scene_mode.ModeSwitcher
import com.omarea.vtools.R
import com.omarea.vtools.kernel.CpuCluster
import com.omarea.vtools.kernel.CpuClusters
import com.omarea.vtools.kernel.Gpu
import com.omarea.vtools.kernel.GpuFrequencyControl
import com.omarea.vtools.kernel.GpuInfo
import com.omarea.vtools.kernel.KernelInfoRow
import com.omarea.vtools.kernel.KernelLoading
import com.omarea.vtools.kernel.KernelRootRequiredChip
import com.omarea.vtools.privilege.PrivilegeManager
import com.omarea.vtools.ui.components.SceneSectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Live summary of the kernel tunables the Scene modes write.
 *
 * Every value is read through the shared kernel helpers ([CpuClusters], [Gpu],
 * [KernelParameters]) on [Dispatchers.IO]; a node the active privilege tier cannot read stays
 * blank and [KernelInfoRow] renders it as "--", so the card works in the Shizuku tier for the
 * CPU/VM rows and marks the GPU rows unavailable instead of failing. The applied mode comes from
 * [ModeSwitcher.getCurrentPowerMode], the same persisted record the mode cards use.
 */
internal data class KernelTuningSnapshot(
    val clusters: List<CpuCluster>,
    val gpu: GpuInfo,
    val swappiness: String,
    val tcpCongestion: String,
    val sceneMode: String
) {
    companion object {
        private const val SWAPPINESS_PATH = "/proc/sys/vm/swappiness"
        private const val TCP_CONGESTION_PATH = "/proc/sys/net/ipv4/tcp_congestion_control"

        fun load(): KernelTuningSnapshot {
            val parameters = KernelParameters.readAll()
            return KernelTuningSnapshot(
                clusters = CpuClusters.load(),
                gpu = Gpu.load(),
                swappiness = parameters[SWAPPINESS_PATH].orEmpty(),
                tcpCongestion = parameters[TCP_CONGESTION_PATH].orEmpty(),
                sceneMode = ModeSwitcher.getCurrentPowerMode()
            )
        }
    }
}

/**
 * Compact "current tuning" card for the Adjust tab.
 *
 * [refreshKey] is bumped by the host when the screen resumes or a mode has been applied; the card
 * reloads whenever it changes. [onRefresh] backs the manual refresh affordance.
 */
@Composable
internal fun KernelTuningCard(
    refreshKey: Int,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    var snapshot by remember { mutableStateOf<KernelTuningSnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(refreshKey) {
        loading = true
        snapshot = withContext(Dispatchers.IO) { KernelTuningSnapshot.load() }
        loading = false
    }

    SceneSectionCard(
        title = stringResource(R.string.kernel_tuning_title),
        iconRes = R.drawable.ic_menu_cpu,
        modifier = modifier,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!PrivilegeManager.hasRootAccess) {
                    KernelRootRequiredChip()
                }
                TextButton(onClick = onRefresh, enabled = !loading) {
                    Text(text = stringResource(R.string.kernel_refresh))
                }
            }
        }
    ) {
        val current = snapshot
        if (current == null) {
            KernelLoading(rows = 5)
        } else {
            KernelInfoRow(
                label = stringResource(R.string.kernel_tuning_scene_mode),
                value = sceneModeLabel(current.sceneMode)
            )
            current.clusters.forEach { cluster ->
                KernelInfoRow(label = clusterLabel(cluster), value = clusterValue(cluster))
            }
            KernelInfoRow(
                label = stringResource(R.string.kernel_section_gpu),
                value = gpuValue(current.gpu)
            )
            KernelInfoRow(
                label = stringResource(R.string.kernel_memory_swappiness),
                value = current.swappiness
            )
            KernelInfoRow(
                label = stringResource(R.string.kernel_network_tcp),
                value = current.tcpCongestion
            )
        }
        Text(
            text = stringResource(R.string.kernel_profiles_scene_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** surya exposes policy0 (cpu0-5, little) and policy6 (cpu6-7, big). */
@Composable
private fun clusterLabel(cluster: CpuCluster): String {
    return stringResource(
        if (cluster.index == 0) R.string.kernel_cluster_little else R.string.kernel_cluster_big
    )
}

@Composable
private fun clusterValue(cluster: CpuCluster): String? {
    if (cluster.governor.isBlank()) {
        return null
    }
    val minMhz = if (cluster.minFreqKHz > 0L) cpuMhz(cluster.minFreqKHz) else null
    val maxMhz = if (cluster.maxFreqKHz > 0L) cpuMhz(cluster.maxFreqKHz) else null
    if (minMhz == null || maxMhz == null) {
        return cluster.governor
    }
    return clockValue(cluster.governor, minMhz, maxMhz)
}

@Composable
private fun gpuValue(gpu: GpuInfo): String? {
    if (!gpu.available || gpu.governor.isBlank()) {
        return null
    }
    val minMhz = gpuMhz(gpu.minFreq)
    val maxMhz = gpuMhz(gpu.maxFreq)
    if (minMhz == null || maxMhz == null) {
        return gpu.governor
    }
    return clockValue(gpu.governor, minMhz, maxMhz)
}

@Composable
private fun clockValue(governor: String, minMhz: String, maxMhz: String): String {
    return stringResource(
        R.string.kernel_tuning_clock_value,
        governor,
        minMhz,
        maxMhz,
        stringResource(R.string.kernel_unit_megahertz)
    )
}

private fun cpuMhz(khz: Long): String = "%.0f".format(khz / 1000.0)

private fun gpuMhz(control: GpuFrequencyControl?): String? {
    control ?: return null
    val mhz = if (control.inMhz) control.current.toDouble() else control.current / 1_000_000.0
    return "%.0f".format(mhz)
}

@Composable
private fun sceneModeLabel(mode: String): String {
    return stringResource(
        when (mode) {
            ModeSwitcher.POWERSAVE -> R.string.powersave
            ModeSwitcher.BALANCE -> R.string.balance
            ModeSwitcher.PERFORMANCE -> R.string.performance
            ModeSwitcher.FAST -> R.string.fast
            ModeSwitcher.IGONED -> R.string.kepp_state
            "" -> R.string.global_default
            else -> R.string.unknown_mode
        }
    )
}
