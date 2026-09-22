package com.omarea.vtools.ui.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.omarea.vtools.R
import com.omarea.vtools.ui.components.SceneNavCard
import com.omarea.vtools.ui.components.SceneSectionHeader
import com.omarea.vtools.ui.theme.SceneSpacing

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

/**
 * The navigation model behind the Features tab.
 *
 * Kept outside the composable so non-Compose callers (for example the click handler in
 * `FragmentNav`, which needs an entry's title for its message) read the exact same list instead of
 * maintaining a parallel copy that drifts.
 */
val overviewSections = listOf(
    OverviewSection(
        titleRes = R.string.menu_section_performance,
        items = listOf(
            OverviewNavItem(R.id.nav_core_control, R.string.menu_core_control, R.drawable.ic_menu_cpu, true),
            OverviewNavItem(R.id.nav_kernel, R.string.menu_kernel, R.drawable.ic_menu_cpu, true),
            OverviewNavItem(R.id.nav_processes, R.string.menu_processes, R.drawable.ic_processes, true),
            OverviewNavItem(R.id.nav_fps_chart, R.string.menu_fps_chart, R.drawable.fw_float_fps, true)
        )
    ),
    OverviewSection(
        titleRes = R.string.menu_section_power,
        items = listOf(
            OverviewNavItem(R.id.nav_charge, R.string.menu_charge, R.drawable.battery, false),
            OverviewNavItem(R.id.nav_power_utilization, R.string.menu_power_utilization, R.drawable.ic_bat_stats, false)
        )
    ),
    OverviewSection(
        titleRes = R.string.menu_section_advanced,
        items = listOf(
            OverviewNavItem(R.id.nav_applictions, R.string.menu_applictions, R.drawable.ic_menu_modules, true),
            OverviewNavItem(R.id.nav_img, R.string.menu_img, R.drawable.ic_menu_img, true),
            OverviewNavItem(R.id.nav_additional, R.string.menu_sundry, R.drawable.ic_menu_vboot, true),
            OverviewNavItem(R.id.nav_additional_all, R.string.menu_additional, R.drawable.ic_menu_shell, true),
            OverviewNavItem(R.id.nav_miui_thermal, R.string.menu_miui_thermal, R.drawable.ic_menu_hot, false),
                OverviewNavItem(R.id.nav_privilege_mode, R.string.menu_privilege_mode, R.drawable.ic_menu_addon, false),
                OverviewNavItem(R.id.nav_setup, R.string.menu_setup, R.drawable.ic_menu_vboot, false)
        )
    )
)

/** The title resource of a navigation entry, or 0 when the id is not part of the Features tab. */
fun overviewNavTitleRes(id: Int): Int {
    return overviewSections.asSequence()
        .flatMap { it.items.asSequence() }
        .firstOrNull { it.id == id }
        ?.titleRes
        ?: 0
}

@Composable
fun OverviewMenu(
    isRootAvailable: Boolean,
    onItemClick: (Int) -> Unit
) {
    val sections = overviewSections

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SceneSpacing.sm, vertical = SceneSpacing.sm)
    ) {
        sections.forEach { section ->
            SceneSectionHeader(title = stringResource(section.titleRes))
            section.items.chunked(2).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SceneSpacing.md)
                ) {
                    rowItems.forEach { item ->
                        val lockedByRoot = item.requiresRoot && !isRootAvailable
                        SceneNavCard(
                            iconRes = item.iconRes,
                            title = stringResource(item.titleRes),
                            // Cards that need root stay clickable: blocking the click would leave a
                            // dimmed entry with no way to explain why it is unavailable. The click
                            // handler shows the reason instead.
                            enabled = true,
                            badge = if (lockedByRoot) stringResource(R.string.menu_requires_root) else null,
                            onClick = { onItemClick(item.id) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (rowItems.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
                Spacer(modifier = Modifier.height(SceneSpacing.md))
            }
            Spacer(modifier = Modifier.height(SceneSpacing.xs))
        }
    }
}
