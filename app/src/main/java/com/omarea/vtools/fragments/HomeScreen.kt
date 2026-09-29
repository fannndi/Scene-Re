package com.omarea.vtools.fragments

import android.content.Context
import android.view.ViewGroup
import android.widget.ListView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.omarea.common.ui.OverScrollGridView
import com.omarea.ui.CpuBigBarView
import com.omarea.ui.CpuChartView
import com.omarea.ui.MemoryChartView
import com.omarea.ui.RamBarView
import com.omarea.utils.AppListHelper
import com.omarea.vtools.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
private fun HomeSectionCard(
    modifier: Modifier = Modifier,
    clickable: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val cardModifier = if (clickable && onClick != null) {
        modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    } else {
        modifier
    }

    Card(
        modifier = cardModifier,
        cornerRadius = 16.dp,
        insideMargin = androidx.compose.foundation.layout.PaddingValues(12.dp),
        colors = CardDefaults.defaultColors()
    ) {
        content()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeScreen(
    state: FragmentHome.HomeUiState,
    cpuGridHeight: Int,
    onMemoryClear: () -> Unit,
    onMemoryCompact: () -> Unit,
    onMemoryCompactLong: () -> Unit,
    onBatteryEdit: () -> Unit,
    onMemoryClick: () -> Unit,
    onBatteryClick: () -> Unit,
    onCpuClick: () -> Unit,
    processListViewFactory: (Context) -> ListView,
    cpuGridViewFactory: (Context) -> OverScrollGridView,
    onRamStatReady: (RamBarView) -> Unit,
    onSwapStatReady: (RamBarView) -> Unit,
    onGpuInfoContainerReady: (ViewGroup) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        HomeSectionCard(
            modifier = Modifier.fillMaxWidth(),
            clickable = true,
            onClick = onMemoryClick
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LoadBar(label = "RAM", percent = state.ramUsedPercent, valueText = state.ramInfoText)
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            AndroidView(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp),
                                factory = { context ->
                                    RamBarView(context).apply {
                                        alpha = 0.4f
                                        onRamStatReady(this)
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Physical",
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                    modifier = Modifier.width(64.dp)
                                )
                                Text(
                                    text = state.ramInfoText,
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        IconButton(onClick = onMemoryClear, modifier = Modifier.size(28.dp)) {
                            Icon(
                                painter = painterResource(R.drawable.icon_clear),
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            AndroidView(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp),
                                factory = { context ->
                                    RamBarView(context).apply {
                                        alpha = 0.4f
                                        onSwapStatReady(this)
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Virtual",
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
                            }
                        }
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
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.alpha(0.75f)
                    ) {
                        Text(
                            text = "SwapCached ",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant
                        )
                        Text(
                            text = state.swapCached,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "Dirty ",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant
                        )
                        Text(
                            text = state.dirty,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        HomeSectionCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LoadBar(label = "GPU", percent = state.gpuLoadPercent, valueText = state.gpuLoadText)
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
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.gpuFreq,
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = state.gpuLoadText,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant
                    )
                    if (state.gpuGovernorText.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = state.gpuGovernorText,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant
                        )
                    }
                    if (state.gpuFreqRangeText.isNotEmpty()) {
                        Text(
                            text = state.gpuFreqRangeText,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant
                        )
                    }
                    if (state.gpuInfoText.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = state.gpuInfoText,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant
                        )
                    }
                }
            }
        }

        HomeSectionCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .padding(start = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AndroidView(
                        modifier = Modifier.weight(1f),
                        factory = { context ->
                            processListViewFactory(context)
                        }
                    )
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(120.dp)
                            .background(MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.3f))
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .combinedClickable(onClick = onCpuClick, onLongClick = null),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 6.dp, top = 6.dp, end = 6.dp, bottom = 0.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Text(
                                text = state.cpuTemperatureText,
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceContainerVariant
                            )
                        }
                        Box(
                            modifier = Modifier
                                .height(85.dp)
                                .width(125.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            LoadBar(label = "CPU", percent = state.cpuLoadPercent, valueText = state.cpuTotalLoad)
                            Text(
                                text = "CPU",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceContainerVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = state.cpuPlatform,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        Text(
                            text = state.cpuTotalLoad,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(cpuGridHeight.dp)
                        .padding(start = 0.dp, end = 0.dp)
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

        HomeSectionCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                ProfileRow(R.drawable.ic_menu_profile, "Mode", state.modeName)
                ProfileRow(R.drawable.ic_menu_cpu, "Cores", state.coresOnline)
                ProfileRow(R.drawable.ic_menu_cpu, "CPU (cur/max MHz)", state.cpuFreqText)
                ProfileRow(R.drawable.fw_float_fps, "GPU (cur/max MHz)", state.gpuFreq.removeSuffix("Mhz") + " / " + state.gpuFreqShort)
                ProfileRow(R.drawable.ic_settings, "Governor", state.governorText)
                ProfileRow(R.drawable.ic_menu_hot, "Thermal", state.thermalText)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .padding(horizontal = 12.dp)
                        .combinedClickable(onClick = onBatteryClick, onLongClick = onBatteryEdit),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_capacity),
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Battery",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = state.batteryCapacity + " · " + state.batteryNow + " · " + state.batteryTemperature,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                }
                ProfileRow(R.drawable.ic_clock, "Uptime", state.runningTime)
                ProfileRow(R.drawable.icon_android, "System", state.deviceName)
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
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
        Spacer(modifier = Modifier.height(4.dp))
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

@Composable
private fun ProfileRow(icon: Int, label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurface
        )
    }
}
