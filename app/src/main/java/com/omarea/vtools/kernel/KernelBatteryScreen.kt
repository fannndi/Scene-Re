package com.omarea.vtools.kernel

import android.os.BatteryManager
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.omarea.vtools.R
import com.omarea.vtools.ui.components.SceneBar
import com.omarea.vtools.ui.components.SceneMetricCard
import com.omarea.vtools.ui.components.SceneSectionCard
import com.omarea.vtools.ui.components.SceneTone
import com.omarea.vtools.ui.theme.SceneSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Battery tab: framework battery telemetry, deep sleep, charging toggles, ZRAM and TCP tuning.
 *
 * Battery values come from the sticky ACTION_BATTERY_CHANGED intent and need no privilege at all;
 * the sysfs-backed rows (charging switches, ZRAM, TCP) are hidden or disabled when the node does
 * not exist or the tier cannot write it. Framework values are re-read on a timer; shell reads only
 * happen on explicit load/refresh, so a denied node is never retried in a loop.
 */
@Composable
internal fun KernelBatteryScreen(refreshKey: Int, hasRoot: Boolean, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var snapshot by remember { mutableStateOf<KernelBatterySnapshot?>(null) }
    var zram by remember { mutableStateOf<ZramInfo?>(null) }
    var tcp by remember { mutableStateOf<TcpInfo?>(null) }
    var thermalProfile by remember { mutableStateOf<String?>(null) }
    var swappiness by remember { mutableStateOf("") }
    var reloadTrigger by remember { mutableIntStateOf(0) }

    var zramSizeDialog by remember { mutableStateOf(false) }
    var zramAlgorithmDialog by remember { mutableStateOf(false) }
    var swappinessDialog by remember { mutableStateOf(false) }
    var tcpDialog by remember { mutableStateOf(false) }
    var thermalDialog by remember { mutableStateOf(false) }

    val writeFailed = stringResource(R.string.kernel_write_failed)

    LaunchedEffect(refreshKey, reloadTrigger) {
        loading = true
        withContext(Dispatchers.IO) {
            snapshot = KernelBattery.loadSnapshot(context)
            zram = KernelMemory.loadZram()
            tcp = KernelMemory.loadTcp()
            thermalProfile = KernelBattery.loadThermalProfile()
            swappiness = KernelMemory.readSwappiness()
        }
        loading = false
    }

    // Framework-only live refresh; intentionally avoids any shell call.
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            val current = snapshot ?: continue
            val framework = withContext(Dispatchers.IO) { KernelBattery.loadFrameworkSnapshot(context) }
            snapshot = current.copy(framework = framework)
        }
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
        if (loading && snapshot == null) {
            KernelLoading()
            return@KernelScreen
        }

        val battery = snapshot ?: return@KernelScreen
        val framework = battery.framework

        SceneSectionCard(
            title = stringResource(R.string.kernel_section_battery),
            iconRes = R.drawable.battery,
            accent = SceneTone.SECONDARY
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(SceneSpacing.md)) {
                SceneMetricCard(
                    label = stringResource(R.string.kernel_battery_level),
                    value = framework.levelPercent?.toString() ?: stringResource(R.string.kernel_value_unavailable),
                    unit = stringResource(R.string.kernel_unit_percent),
                    modifier = Modifier.weight(1f),
                    tone = SceneTone.PRIMARY
                )
                SceneMetricCard(
                    label = stringResource(R.string.kernel_battery_temperature),
                    value = formatTemperature(framework.temperatureTenths)
                        ?: stringResource(R.string.kernel_value_unavailable),
                    modifier = Modifier.weight(1f),
                    tone = SceneTone.TERTIARY
                )
            }
            if (framework.levelPercent != null) {
                SceneBar(
                    fraction = framework.levelPercent / 100f,
                    tone = if (framework.levelPercent <= 15) SceneTone.ERROR else SceneTone.PRIMARY
                )
            }
            Spacer(modifier = Modifier.height(SceneSpacing.xs))
            KernelInfoRow(stringResource(R.string.kernel_battery_status), statusLabel(framework.statusCode))
            KernelInfoRow(stringResource(R.string.kernel_battery_health), healthLabel(framework.healthCode))
            KernelInfoRow(stringResource(R.string.kernel_battery_technology), framework.technology)
            KernelInfoRow(stringResource(R.string.kernel_battery_voltage), formatVoltage(framework.voltageMv))
            KernelInfoRow(stringResource(R.string.kernel_battery_current), formatCurrent(framework.currentUa))
            KernelInfoRow(
                stringResource(R.string.kernel_battery_design_capacity),
                formatCapacity(battery.designCapacityUah)
            )
            KernelInfoRow(
                stringResource(R.string.kernel_battery_full_capacity),
                formatCapacity(battery.fullCapacityUah)
            )
            val deepSleepMillis = battery.deepSleepMillis
                ?: (SystemClock.elapsedRealtime() - SystemClock.uptimeMillis())
            val elapsed = SystemClock.elapsedRealtime()
            val deepSleepPercent = if (elapsed > 0L) (deepSleepMillis * 100 / elapsed).toInt() else 0
            KernelInfoRow(
                stringResource(R.string.kernel_battery_deep_sleep),
                if (deepSleepMillis > 0L) {
                    stringResource(
                        R.string.kernel_battery_deep_sleep_value,
                        formatDuration(deepSleepMillis),
                        deepSleepPercent
                    )
                } else {
                    null
                }
            )
        }

        val thermal = thermalProfile
        if (thermal != null) {
            SceneSectionCard(
                title = stringResource(R.string.kernel_section_thermal),
                iconRes = R.drawable.ic_menu_hot,
                trailing = { if (!hasRoot) KernelRootRequiredChip() }
            ) {
                KernelActionRow(
                    title = stringResource(R.string.kernel_thermal_profile),
                    value = thermalProfileLabel(thermal),
                    enabled = hasRoot
                ) { thermalDialog = true }
            }
        }

        val zramState = zram
        val tcpState = tcp
        SceneSectionCard(
            title = stringResource(R.string.kernel_section_memory),
            iconRes = R.drawable.ic_processes,
            trailing = { if (!hasRoot) KernelRootRequiredChip() }
        ) {
            if (zramState == null || !zramState.available) {
                Text(
                    text = stringResource(R.string.kernel_memory_zram_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                KernelActionRow(
                    title = stringResource(R.string.kernel_memory_zram_size),
                    value = if (zramState.disksizeBytes > 0L) {
                        KernelShell.formatBytes(zramState.disksizeBytes)
                    } else {
                        stringResource(R.string.kernel_value_unavailable)
                    },
                    enabled = hasRoot
                ) { zramSizeDialog = true }
                KernelActionRow(
                    title = stringResource(R.string.kernel_memory_zram_algorithm),
                    value = zramState.compAlgorithm.ifBlank { stringResource(R.string.kernel_value_unavailable) },
                    enabled = hasRoot && zramState.availableCompAlgorithms.isNotEmpty()
                ) { zramAlgorithmDialog = true }
            }
            KernelActionRow(
                title = stringResource(R.string.kernel_memory_swappiness),
                value = swappiness.ifBlank { stringResource(R.string.kernel_value_unavailable) },
                enabled = hasRoot
            ) { swappinessDialog = true }
        }

        if (tcpState != null && tcpState.available) {
            SceneSectionCard(
                title = stringResource(R.string.kernel_section_network),
                iconRes = R.drawable.ic_menu_shell,
                trailing = { if (!hasRoot) KernelRootRequiredChip() }
            ) {
                KernelActionRow(
                    title = stringResource(R.string.kernel_network_tcp),
                    value = tcpState.congestionControl.ifBlank { stringResource(R.string.kernel_value_unavailable) },
                    enabled = hasRoot && tcpState.availableAlgorithms.isNotEmpty()
                ) { tcpDialog = true }
            }
        }
    }

    val zramState = zram
    if (zramSizeDialog && zramState != null) {
        val currentSizeGb = (zramState.disksizeBytes / (1024.0 * 1024 * 1024)).toInt().coerceAtLeast(1)
        KernelInputDialog(
            title = stringResource(R.string.kernel_memory_zram_size),
            initialValue = currentSizeGb.toString(),
            numericOnly = true,
            isValid = { value ->
                val gigabytes = value.toIntOrNull()
                gigabytes != null && gigabytes in 1..8
            },
            onDismiss = { zramSizeDialog = false },
            onConfirm = { value ->
                val gigabytes = value.toIntOrNull() ?: return@KernelInputDialog
                val bytes = gigabytes * 1024L * 1024 * 1024
                write { KernelMemory.setZramSize(bytes) }
            }
        )
    }

    if (zramAlgorithmDialog && zramState != null) {
        KernelOptionDialog(
            title = stringResource(R.string.kernel_memory_zram_algorithm),
            options = zramState.availableCompAlgorithms,
            selected = zramState.compAlgorithm,
            label = { it },
            onDismiss = { zramAlgorithmDialog = false },
            onSelect = { algorithm -> write { KernelMemory.setZramCompAlgorithm(algorithm, zramState.disksizeBytes) } }
        )
    }

    if (swappinessDialog) {
        KernelInputDialog(
            title = stringResource(R.string.kernel_memory_swappiness),
            initialValue = swappiness.ifBlank { "60" },
            numericOnly = true,
            isValid = { value ->
                val numeric = value.toIntOrNull()
                numeric != null && numeric in 0..100
            },
            onDismiss = { swappinessDialog = false },
            onConfirm = { value -> write { KernelMemory.writeSwappiness(value) } }
        )
    }

    if (tcpDialog) {
        val tcpState = tcp
        if (tcpState != null) {
            KernelOptionDialog(
                title = stringResource(R.string.kernel_network_tcp),
                options = tcpState.availableAlgorithms,
                selected = tcpState.congestionControl,
                label = { it },
                onDismiss = { tcpDialog = false },
                onSelect = { algorithm -> write { KernelMemory.setTcpCongestion(algorithm) } }
            )
        }
    }

    if (thermalDialog) {
        KernelOptionDialog(
            title = stringResource(R.string.kernel_thermal_profile),
            options = KernelBattery.THERMAL_PROFILE_IDS,
            selected = thermalProfile,
            label = { thermalProfileLabel(it) },
            onDismiss = { thermalDialog = false },
            onSelect = { profile -> write { KernelBattery.setThermalProfile(profile) } }
        )
    }
}

@Composable
private fun statusLabel(code: Int): String {
    return when (code) {
        BatteryManager.BATTERY_STATUS_CHARGING -> stringResource(R.string.kernel_status_charging)
        BatteryManager.BATTERY_STATUS_DISCHARGING -> stringResource(R.string.kernel_status_discharging)
        BatteryManager.BATTERY_STATUS_FULL -> stringResource(R.string.kernel_status_full)
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> stringResource(R.string.kernel_status_not_charging)
        else -> stringResource(R.string.kernel_status_unknown)
    }
}

@Composable
private fun healthLabel(code: Int): String {
    return when (code) {
        BatteryManager.BATTERY_HEALTH_GOOD -> stringResource(R.string.kernel_health_good)
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> stringResource(R.string.kernel_health_overheat)
        BatteryManager.BATTERY_HEALTH_DEAD -> stringResource(R.string.kernel_health_dead)
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> stringResource(R.string.kernel_health_over_voltage)
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> stringResource(R.string.kernel_health_unspecified)
        BatteryManager.BATTERY_HEALTH_COLD -> stringResource(R.string.kernel_health_cold)
        else -> stringResource(R.string.kernel_status_unknown)
    }
}

/** MIUI thermal profile names; ids are shared with the kernel profile scripts. */
@Composable
private fun thermalProfileLabel(id: String): String {
    return when (id) {
        "0" -> stringResource(R.string.kernel_thermal_default)
        "8" -> stringResource(R.string.kernel_thermal_dialer)
        "10" -> stringResource(R.string.kernel_thermal_benchmark)
        "11" -> stringResource(R.string.kernel_thermal_browser)
        "12" -> stringResource(R.string.kernel_thermal_camera)
        "13" -> stringResource(R.string.kernel_thermal_gaming)
        "14" -> stringResource(R.string.kernel_thermal_streaming)
        else -> id
    }
}
