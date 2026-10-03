package com.omarea.ui.overview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.omarea.ui.theme.SceneDimens
import com.omarea.ui.theme.ScenePalette
import com.omarea.vtools.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

data class OverviewNavItem(
    val id: Int,
    val titleRes: Int,
    val iconRes: Int,
    val requiresRoot: Boolean
)

data class OverviewSection(
    val titleRes: Int,
    val items: List<OverviewNavItem>
)

@Composable
fun OverviewMenu(
    isRootAvailable: Boolean,
    showMiuiThermal: Boolean = true,
    onItemClick: (Int) -> Unit
) {
    val utilities = mutableListOf(
        OverviewNavItem(R.id.nav_swap, R.string.menu_swap, R.drawable.memory_mem, true),
        OverviewNavItem(R.id.nav_freeze, R.string.menu_freeze, R.drawable.freeze_logo, true)
    )
    if (showMiuiThermal) {
        utilities += OverviewNavItem(R.id.nav_miui_thermal, R.string.menu_miui_thermal, R.drawable.ic_temperature, true)
    }

    val sections = listOf(
        OverviewSection(
            titleRes = R.string.menu_section_monitor,
            items = listOf(
                OverviewNavItem(R.id.nav_processes, R.string.menu_processes, R.drawable.ic_processes, true),
                OverviewNavItem(R.id.nav_fps_chart, R.string.menu_fps_chart, R.drawable.fw_float_fps, true),
                OverviewNavItem(R.id.nav_benchmark, R.string.menu_benchmark, R.drawable.ic_bat_stats, true),
                OverviewNavItem(R.id.nav_charge, R.string.menu_charge, R.drawable.battery, false),
                OverviewNavItem(R.id.nav_power_utilization, R.string.menu_power_utilization, R.drawable.ic_bat_stats, false)
            )
        ),
        OverviewSection(
            titleRes = R.string.menu_section_utilities,
            items = utilities
        ),
        OverviewSection(
            titleRes = R.string.menu_section_advanced,
            items = listOf(
                OverviewNavItem(R.id.nav_automation, R.string.menu_automation, R.drawable.ic_clock, true),
                OverviewNavItem(R.id.nav_additional_all, R.string.menu_additional, R.drawable.ic_menu_shell, true),
                OverviewNavItem(R.id.nav_diagnostics, R.string.menu_diagnostics, R.drawable.ic_settings, false)
            )
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SceneDimens.screenH, vertical = SceneDimens.screenVTop)
    ) {
        sections.forEach { section ->
            Text(
                text = stringResource(section.titleRes),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(SceneDimens.spaceS))
            section.items.chunked(2).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SceneDimens.cardGap)
                ) {
                    rowItems.forEach { item ->
                        OverviewMenuItem(
                            item = item,
                            // Dimmed when root is missing, but ALWAYS clickable:
                            // tapping prompts a root grant instead of dying silently.
                            rootAvailable = isRootAvailable,
                            onClick = onItemClick,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (rowItems.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
                Spacer(modifier = Modifier.height(SceneDimens.cardGap))
            }
            Spacer(modifier = Modifier.height(SceneDimens.spaceXs))
        }
    }
}

@Composable
private fun OverviewMenuItem(
    item: OverviewNavItem,
    rootAvailable: Boolean,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // Root missing: say so instead of the old 40 % alpha that read as
    // "disabled" — the tile stays clickable and prompts for the root grant.
    val missingRoot = item.requiresRoot && !rootAvailable
    Card(
        modifier = modifier
            .heightIn(min = 64.dp)
            .alpha(if (missingRoot) 0.9f else 1f)
            .clickable { onClick(item.id) },
        cornerRadius = SceneDimens.cardRadius,
        insideMargin = androidx.compose.foundation.layout.PaddingValues(horizontal = SceneDimens.cardPadding, vertical = SceneDimens.spaceM),
        colors = CardDefaults.defaultColors()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = SceneDimens.spaceS),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(item.iconRes),
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(SceneDimens.iconSize)
            )
            Spacer(modifier = Modifier.width(SceneDimens.iconGap))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(item.titleRes),
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurface
                )
                if (missingRoot) {
                    Text(
                        text = stringResource(R.string.menu_needs_root),
                        style = MiuixTheme.textStyles.footnote2,
                        color = ScenePalette.amber
                    )
                }
            }
        }
    }
}
