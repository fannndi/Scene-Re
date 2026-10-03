package com.omarea.ui.screen

import android.content.Context
import android.view.ViewGroup
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.omarea.common.ui.OverScrollGridView
import com.omarea.ui.home.HomeUiState
import com.omarea.ui.theme.SceneDimens
import com.omarea.ui.theme.ScenePalette
import com.omarea.vtools.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** One row of the first-run checklist (icon + label + status, tappable). */
@Composable
private fun SetupStep(title: String, done: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SceneDimens.cardRadius))
            .clickable(onClick = onClick)
            .padding(vertical = SceneDimens.spaceXs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(if (done) R.drawable.check else R.drawable.add),
            contentDescription = null,
            tint = if (done) ScenePalette.green else ScenePalette.amber,
            modifier = Modifier.size(SceneDimens.iconSize)
        )
        Spacer(modifier = Modifier.width(SceneDimens.iconGap))
        Text(
            text = title,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = stringResource(if (done) R.string.setup_status_done else R.string.setup_status_todo),
            style = MiuixTheme.textStyles.footnote2,
            color = if (done) ScenePalette.green else MiuixTheme.colorScheme.primary
        )
    }
}

@Composable
private fun HomeCard(
    modifier: Modifier = Modifier.fillMaxWidth(),
    clickable: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val cardModifier = if (clickable && onClick != null) {
        modifier
            .clip(RoundedCornerShape(SceneDimens.cardRadius))
            .clickable(onClick = onClick)
    } else {
        modifier
    }

    Card(
        modifier = cardModifier,
        cornerRadius = SceneDimens.cardRadius,
        insideMargin = PaddingValues(SceneDimens.cardPadding),
        colors = CardDefaults.defaultColors()
    ) {
        content()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeScreen(
    state: HomeUiState,
    cpuGridHeight: Int,
    onMemoryClear: () -> Unit,
    onMemoryCompact: () -> Unit,
    onMemoryCompactLong: () -> Unit,
    onBatteryEdit: () -> Unit,
    onMemoryClick: () -> Unit,
    onBatteryClick: () -> Unit,
    onCpuClick: () -> Unit,
    onModeClick: () -> Unit,
    onRootWarningClick: () -> Unit,
    onEngineRestoreClick: () -> Unit,
    onSetupRoot: () -> Unit,
    onSetupA11y: () -> Unit,
    onSetupEngine: () -> Unit,
    cpuGridViewFactory: (Context) -> OverScrollGridView,
    onGpuInfoContainerReady: (ViewGroup) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = SceneDimens.screenH,
                end = SceneDimens.screenH,
                top = SceneDimens.screenVTop,
                bottom = SceneDimens.screenVBottom
            ),
        verticalArrangement = Arrangement.spacedBy(SceneDimens.cardGap)
    ) {
        // First-run checklist: one card until root + accessibility + engine
        // are all done (it doubles as the Monitor-mode explainer). Hidden
        // while TRUE OFF or the restore-prompt card already speaks.
        val setupIncomplete = state.rootMissing || !state.setupA11yDone || !state.setupEngineOn
        if (!state.trueOff && !state.engineRestorePending && setupIncomplete) {
            HomeCard {
                Column {
                    Text(
                        text = stringResource(R.string.setup_title),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(SceneDimens.spaceS))
                    SetupStep(
                        title = stringResource(R.string.setup_root),
                        done = !state.rootMissing,
                        onClick = onSetupRoot
                    )
                    SetupStep(
                        title = stringResource(R.string.setup_a11y),
                        done = state.setupA11yDone,
                        onClick = onSetupA11y
                    )
                    SetupStep(
                        title = stringResource(R.string.setup_engine),
                        done = state.setupEngineOn,
                        onClick = onSetupEngine
                    )
                }
            }
        }

        // TRUE OFF is controlled from Tuner ▸ Profile; Home only warns when it
        // is active so the user knows why nothing is being tuned.
        if (state.trueOff) {
            HomeCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.power_shutdown),
                        contentDescription = null,
                        tint = ScenePalette.red,
                        modifier = Modifier.size(SceneDimens.iconSize)
                    )
                    Spacer(modifier = Modifier.width(SceneDimens.iconGap))
                    Text(
                        text = stringResource(R.string.true_off_on),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // Monitor mode (no root): everything stays stock — warn instead of
        // showing a tuned-looking UI. Tap opens Diagnostics.
        if (state.rootMissing) {
            HomeCard(clickable = true, onClick = onRootWarningClick) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.power_shutdown),
                        contentDescription = null,
                        tint = ScenePalette.amber,
                        modifier = Modifier.size(SceneDimens.iconSize)
                    )
                    Spacer(modifier = Modifier.width(SceneDimens.iconGap))
                    Text(
                        text = stringResource(R.string.home_no_root_warning),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                }
            }
        } else if (state.engineRestorePending) {
            // Root is back after a no-root boot: one tap restores the engine
            // (the pref was auto-disabled while root was missing).
            HomeCard(clickable = true, onClick = onEngineRestoreClick) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.power_reboot),
                        contentDescription = null,
                        tint = ScenePalette.green,
                        modifier = Modifier.size(SceneDimens.iconSize)
                    )
                    Spacer(modifier = Modifier.width(SceneDimens.iconGap))
                    Text(
                        text = stringResource(R.string.home_engine_restore_pending),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // Engine ON but this boot never applied (MIUI autostart blocked the
        // receiver): warn with the fix instead of showing a tuned-looking UI.
        if (state.bootApplyWarning) {
            HomeCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.power_reboot),
                        contentDescription = null,
                        tint = ScenePalette.amber,
                        modifier = Modifier.size(SceneDimens.iconSize)
                    )
                    Spacer(modifier = Modifier.width(SceneDimens.iconGap))
                    Text(
                        text = stringResource(R.string.home_boot_warning),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                }
            }
        }

        HomeCard(clickable = true, onClick = onMemoryClick) {
            Column {
                LoadBar(label = "RAM", percent = state.ramUsedPercent, valueText = state.ramInfoText)
                Spacer(modifier = Modifier.height(SceneDimens.spaceM))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Swap",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        modifier = Modifier.width(64.dp)
                    )
                    Text(
                        text = state.zramInfoText,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .combinedClickable(
                                onClick = onMemoryCompact,
                                onLongClick = onMemoryCompactLong
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.icon_harddisk),
                            contentDescription = stringResource(R.string.desc_mem_compact),
                            tint = ScenePalette.blue
                        )
                    }
                    Spacer(modifier = Modifier.width(SceneDimens.spaceS))
                    IconButton(onClick = onMemoryClear, modifier = Modifier.size(28.dp)) {
                        Icon(
                            painter = painterResource(R.drawable.icon_clear),
                            contentDescription = stringResource(R.string.desc_mem_clear),
                            tint = ScenePalette.red
                        )
                    }
                }
            }
        }

        HomeCard {
            LoadBar(label = "GPU", percent = state.gpuLoadPercent, valueText = state.gpuFreq)
            Box(modifier = Modifier.size(1.dp)) {
                AndroidView(
                    modifier = Modifier.size(1.dp),
                    factory = { context ->
                        android.widget.FrameLayout(context).apply {
                            alpha = 0.05f
                            onGpuInfoContainerReady(this)
                        }
                    }
                )
            }
        }

        HomeCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = onCpuClick, onLongClick = null)
            ) {
                LoadBar(label = "CPU", percent = state.cpuLoadPercent, valueText = state.cpuTotalLoad)
                Spacer(modifier = Modifier.height(SceneDimens.spaceS))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = state.cpuPlatform,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    Text(
                        text = state.cpuTemperatureText,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant
                    )
                }
                Text(
                    text = state.socText,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant
                )
                Text(
                    text = state.cpuArchText,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant
                )
                Spacer(modifier = Modifier.height(SceneDimens.spaceS))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(cpuGridHeight.dp)
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            cpuGridViewFactory(context)
                        }
                    )
                }
                if (state.perfBoostText.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(SceneDimens.spaceS))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.home_perf_lock),
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = state.perfBoostText,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // Live state: single-line rows for scalars, left-aligned blocks for
        // the multi-line CPU/GPU details (no ragged right wrapping).
        HomeCard {
            Column {
                ProfileRow(R.drawable.ic_menu_profile, "Mode", state.modeName, tint = ScenePalette.blue, onClick = onModeClick)
                ProfileBlock(R.drawable.ic_menu_cpu, "CPU 0\u20135 · Silver", state.cluster0Text, tint = ScenePalette.green)
                ProfileBlock(R.drawable.ic_menu_cpu, "CPU 6\u20137 · Gold", state.cluster6Text, tint = ScenePalette.lime)
                ProfileBlock(R.drawable.fw_float_fps, "GPU · Adreno 618", state.gpuDetailText, tint = ScenePalette.amber)
                ProfileRow(R.drawable.ic_menu_hot, "Thermal", state.thermalText, tint = ScenePalette.orange)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = SceneDimens.rowMinHeight)
                        .combinedClickable(onClick = onBatteryClick, onLongClick = onBatteryEdit),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_capacity),
                        contentDescription = null,
                        tint = ScenePalette.green,
                        modifier = Modifier.size(SceneDimens.iconSize)
                    )
                    Spacer(modifier = Modifier.width(SceneDimens.iconGap))
                    Text(
                        text = "Battery",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = state.batteryCapacity + " · " + state.batteryNow + " · " + state.batteryTemperature,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurface,
                        textAlign = TextAlign.End
                    )
                }
                ProfileRow(R.drawable.ic_clock, "Uptime", state.runningTime, tint = ScenePalette.violet)
                ProfileRow(R.drawable.icon_android, "System", state.deviceName, tint = ScenePalette.slate)
            }
        }
    }
}

@Composable
private fun LoadBar(label: String, percent: Int, valueText: String) {
    val clamped = percent.coerceIn(0, 100)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = valueText,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.height(SceneDimens.spaceXs))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.25f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(clamped / 100f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MiuixTheme.colorScheme.primary)
            )
        }
    }
}

/** Single-line setting row: icon + label left, value right. */
@Composable
private fun ProfileRow(
    icon: Int,
    label: String,
    value: String,
    tint: Color = ScenePalette.slate,
    onClick: (() -> Unit)? = null
) {
    val base = Modifier
        .fillMaxWidth()
        .height(SceneDimens.rowMinHeight)
    Row(
        modifier = if (onClick != null) base.clickable(onClick = onClick) else base,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(SceneDimens.iconSize)
        )
        Spacer(modifier = Modifier.width(SceneDimens.iconGap))
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurface,
            textAlign = TextAlign.End
        )
    }
}

/**
 * Multi-line detail block: icon + label on the first line, detail lines
 * left-aligned underneath (aligned with the label text).
 */
@Composable
private fun ProfileBlock(icon: Int, label: String, detail: String, tint: Color = ScenePalette.slate) {
    if (detail.isEmpty()) return
    val lines = detail.split('\n')
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SceneDimens.spaceS)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(SceneDimens.iconSize)
            )
            Spacer(modifier = Modifier.width(SceneDimens.iconGap))
            Text(
                text = label,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurface
            )
        }
        Text(
            text = lines.joinToString("\n"),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            modifier = Modifier.padding(
                start = SceneDimens.iconSize + SceneDimens.iconGap,
                top = SceneDimens.spaceXs
            )
        )
    }
}
