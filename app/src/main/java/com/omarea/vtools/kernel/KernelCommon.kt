package com.omarea.vtools.kernel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import com.omarea.vtools.R
import com.omarea.vtools.ui.components.SceneChip
import com.omarea.vtools.ui.components.SceneKeyValueRow
import com.omarea.vtools.ui.components.SceneSkeleton
import com.omarea.vtools.ui.components.SceneTone
import com.omarea.vtools.ui.theme.SceneSpacing

/**
 * Shared building blocks for the Kernel manager screens.
 *
 * Screens are plain Compose functions with no ViewModel: each one loads its data in a
 * `LaunchedEffect` on [kotlinx.coroutines.Dispatchers.IO] and renders immutable state, so the UI can
 * never block on a shell call.
 */

/** Scrollable, padded screen body used by every Kernel tab. */
@Composable
internal fun KernelScreen(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SceneSpacing.lg, vertical = SceneSpacing.md),
        verticalArrangement = Arrangement.spacedBy(SceneSpacing.md),
        content = content
    )
}

/** Shimmer placeholder shown while the first read is in flight. */
@Composable
internal fun KernelLoading(rows: Int = 5) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SceneSpacing.sm)
    ) {
        repeat(rows) {
            SceneSkeleton(height = 20)
        }
    }
}

/** Key/value row that renders a dash when the value is missing instead of showing nothing. */
@Composable
internal fun KernelInfoRow(label: String, value: String?, modifier: Modifier = Modifier) {
    SceneKeyValueRow(
        key = label,
        value = value?.takeIf { it.isNotBlank() } ?: stringResource(R.string.kernel_value_unavailable),
        modifier = modifier
    )
}

/** "Root required" chip shown next to controls that need uid 0. */
@Composable
internal fun KernelRootRequiredChip() {
    SceneChip(
        label = stringResource(R.string.kernel_root_required),
        tone = SceneTone.WARNING
    )
}

@Composable
internal fun formatTemperature(tenths: Int?): String? {
    return tenths?.let { "%.1f %s".format(it / 10.0, stringResource(R.string.kernel_unit_celsius)) }
}

@Composable
internal fun formatVoltage(millivolts: Int?): String? {
    return millivolts?.let { "%.3f %s".format(it / 1000.0, stringResource(R.string.kernel_unit_voltage)) }
}

@Composable
internal fun formatCurrent(microamps: Long?): String? {
    // Values above 10 A can only be garbage from a kernel that reports in another unit.
    val value = microamps?.takeIf { it in -10_000_000L..10_000_000L } ?: return null
    val milliamps = value / 1000.0
    return if (milliamps > -1000 && milliamps < 1000) {
        "%.0f %s".format(milliamps, stringResource(R.string.kernel_unit_milliampere))
    } else {
        "%.2f %s".format(milliamps / 1000.0, stringResource(R.string.kernel_unit_ampere))
    }
}

@Composable
internal fun formatCapacity(microampHours: Long?): String? {
    return microampHours?.takeIf { it > 0L }?.let {
        "%.0f %s".format(it / 1000.0, stringResource(R.string.kernel_unit_milliampere_hour))
    }
}

@Composable
internal fun formatCpuFrequency(khz: Long): String {
    return if (khz >= 1_000_000L) {
        "%.2f %s".format(khz / 1_000_000.0, stringResource(R.string.kernel_unit_gigahertz))
    } else {
        "%d %s".format(khz / 1000, stringResource(R.string.kernel_unit_megahertz))
    }
}

@Composable
internal fun formatGpuFrequency(control: GpuFrequencyControl, value: Long): String {
    return if (control.inMhz) {
        "%d %s".format(value, stringResource(R.string.kernel_unit_megahertz))
    } else {
        "%d %s".format(value / 1_000_000, stringResource(R.string.kernel_unit_megahertz))
    }
}

/** Formats a millisecond duration as `1d 2h 3m 4s`. */
@Composable
internal fun formatDuration(millis: Long): String {
    val seconds = millis / 1000
    val days = seconds / (24 * 60 * 60)
    val hours = (seconds / (60 * 60)) % 24
    val minutes = (seconds / 60) % 60
    val remainingSeconds = seconds % 60
    val builder = StringBuilder()
    if (days > 0) {
        builder.append(stringResource(R.string.kernel_duration_days, days)).append(' ')
    }
    if (hours > 0 || days > 0) {
        builder.append(stringResource(R.string.kernel_duration_hours, hours)).append(' ')
    }
    if (minutes > 0 || hours > 0 || days > 0) {
        builder.append(stringResource(R.string.kernel_duration_minutes, minutes)).append(' ')
    }
    builder.append(stringResource(R.string.kernel_duration_seconds, remainingSeconds))
    return builder.toString().trim()
}

/** Switch row with a title/summary block; used by the charging and throttling controls. */
@Composable
internal fun KernelSwitchRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            if (summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** Read-only row with an action value and a chevron, opening a picker dialog. */
@Composable
internal fun KernelActionRow(
    title: String,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = SceneSpacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = SceneSpacing.sm),
            maxLines = 1,
            textAlign = TextAlign.End
        )
        Text(
            text = stringResource(R.string.kernel_chevron),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Single-select dialog used for governors, frequencies, power levels and algorithms. */
@Composable
internal fun <T> KernelOptionDialog(
    title: String,
    options: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(SceneSpacing.xs)
            ) {
                options.forEach { option ->
                    FilterChip(
                        selected = option == selected,
                        onClick = {
                            onSelect(option)
                            onDismiss()
                        },
                        label = { Text(label(option)) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}

/** Free-text/int input dialog; [isValid] rejects values before they reach the shell. */
@Composable
internal fun KernelInputDialog(
    title: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    numericOnly: Boolean = false,
    isValid: (String) -> Boolean = { true }
) {
    var value by remember { mutableStateOf(initialValue) }
    val valid = value.isNotBlank() && isValid(value)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                isError = value.isNotBlank() && !isValid(value),
                keyboardOptions = if (numericOnly) {
                    KeyboardOptions(keyboardType = KeyboardType.Number)
                } else {
                    KeyboardOptions.Default
                }
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(value.trim())
                    onDismiss()
                },
                enabled = valid
            ) {
                Text(stringResource(R.string.btn_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}
