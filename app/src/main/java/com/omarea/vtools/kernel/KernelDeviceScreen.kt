package com.omarea.vtools.kernel

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.omarea.vtools.R
import com.omarea.vtools.ui.components.SceneSectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Device tab: hardware and kernel identity.
 *
 * Everything here is read-only and mostly framework-backed, so the tab is fully populated even
 * without root; kernel-specific values fall back to a dash when the shell cannot read them.
 */
@Composable
internal fun KernelDeviceScreen(refreshKey: Int) {
    var info by remember { mutableStateOf<KernelDeviceInfo?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(refreshKey) {
        loading = true
        info = withContext(Dispatchers.IO) { KernelDeviceInfoReader.load() }
        loading = false
    }

    KernelScreen {
        if (loading && info == null) {
            KernelLoading()
            return@KernelScreen
        }
        val device = info ?: return@KernelScreen

        SceneSectionCard(
            title = stringResource(R.string.kernel_section_device),
            iconRes = R.drawable.graph
        ) {
            KernelInfoRow(stringResource(R.string.kernel_device_model), device.model)
            KernelInfoRow(stringResource(R.string.kernel_device_codename), device.codename)
            KernelInfoRow(stringResource(R.string.kernel_device_manufacturer), device.manufacturer)
            KernelInfoRow(stringResource(R.string.kernel_device_soc), device.soc)
            if (device.socModel.isNotEmpty()) {
                KernelInfoRow(stringResource(R.string.kernel_device_soc_model), device.socModel)
            }
            KernelInfoRow(
                stringResource(R.string.kernel_device_android),
                stringResource(R.string.kernel_device_android_value, device.androidVersion, device.sdkInt)
            )
            KernelInfoRow(stringResource(R.string.kernel_device_gpu), device.gpuModel)
        }

        SceneSectionCard(
            title = stringResource(R.string.kernel_section_kernel),
            iconRes = R.drawable.ic_menu_shell
        ) {
            KernelInfoRow(stringResource(R.string.kernel_device_kernel), device.kernelVersion)
            KernelInfoRow(
                stringResource(R.string.kernel_device_ram),
                if (device.ramTotalBytes > 0L) KernelShell.formatBytes(device.ramTotalBytes) else null
            )
            KernelInfoRow(
                stringResource(R.string.kernel_device_wireguard),
                if (device.wireGuardAvailable) device.wireGuardVersion else null
            )
        }

        SceneSectionCard(title = stringResource(R.string.kernel_section_kernel_build)) {
            Text(
                text = device.kernelFullVersion.ifBlank { stringResource(R.string.kernel_value_unavailable) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
