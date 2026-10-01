package com.omarea.ui.screen

import android.content.Context
import android.view.ViewGroup
import android.widget.ListView
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
    processListViewFactory: (Context) -> ListView,
    cpuGridViewFactory: (Context) -> OverScrollGridView,
    onGpuInfoContainerReady: (ViewGroup) -> Unit,
    onTrueOffToggle: (Boolean) -> Unit
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
        // TRUE OFF master switch: stops every actuator (reads stay alive).
        HomeCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.true_off_title),
                        style = MiuixTheme.textStyles.body1,
                        color = if (state.trueOff) MiuixTheme.colorScheme.onSurface
                        else MiuixTheme.colorScheme.onSurfaceContainerVariant
                    )
                    Spacer(modifier = Modifier.height(SceneDimens.spaceXs))
                    Text(
                        text = stringResource(
                            if (state.trueOff) R.string.true_off_desc_on else R.string.true_off_desc_off
                        ),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant
                    )
                }
                Spacer(modifier = Modifier.width(SceneDimens.spaceM))
                androidx.compose.material3.Switch(
                    checked = state.trueOff,
                    onCheckedChange = onTrueOffToggle
                )
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
                            contentDescription = null,
                            tint = ScenePalette.blue
                        )
                    }
                    Spacer(modifier = Modifier.width(SceneDimens.spaceS))
                    IconButton(onClick = onMemoryClear, modifier = Modifier.size(28.dp)) {
                        Icon(
                            painter = painterResource(R.drawable.icon_clear),
                            contentDescription = null,
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
