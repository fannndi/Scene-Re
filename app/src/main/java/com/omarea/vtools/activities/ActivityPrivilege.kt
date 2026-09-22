package com.omarea.vtools.activities

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.omarea.vtools.R
import com.omarea.vtools.privilege.PrivilegeManager
import com.omarea.vtools.privilege.PrivilegeTier
import com.omarea.vtools.ui.components.SceneCard
import com.omarea.vtools.ui.components.SceneSectionHeader
import com.omarea.vtools.ui.theme.SceneSpacing
import com.omarea.vtools.ui.theme.SceneTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Privilege mode selector: Root / Shizuku / Non-root.
 *
 * The selected tier is persisted by [PrivilegeManager] and routed into every shell command.
 */
class ActivityPrivilege : ActivityBase() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val composeView = ComposeView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }
        setContentView(composeView)
        composeView.setContent {
            SceneTheme(mode = themeMode) {
                PrivilegeScreen()
            }
        }
    }

    @Composable
    private fun PrivilegeScreen() {
        val context = this
        var revision by remember { mutableIntStateOf(0) }
        var busy by remember { mutableStateOf(false) }
        var selectedTier by remember { mutableStateOf(PrivilegeManager.tier) }
        var rootAvailable by remember { mutableStateOf(PrivilegeManager.rootAvailable) }
        var shizukuAvailable by remember { mutableStateOf(PrivilegeManager.shizukuAvailable) }
        var shizukuGranted by remember { mutableStateOf(PrivilegeManager.shizukuPermissionGranted) }
        var shizukuInstalled by remember { mutableStateOf(PrivilegeManager.isShizukuInstalled(context)) }

        LaunchedEffect(revision) {
            busy = true
            withContext(Dispatchers.IO) {
                PrivilegeManager.detectRoot()
            }
            PrivilegeManager.refreshShizuku()
            selectedTier = PrivilegeManager.tier
            rootAvailable = PrivilegeManager.rootAvailable
            shizukuAvailable = PrivilegeManager.shizukuAvailable
            shizukuGranted = PrivilegeManager.shizukuPermissionGranted
            shizukuInstalled = PrivilegeManager.isShizukuInstalled(context)
            busy = false
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(SceneSpacing.lg)
        ) {
            Text(
                text = stringResource(R.string.privilege_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(SceneSpacing.xs))
            Text(
                text = stringResource(R.string.privilege_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(SceneSpacing.md))

            SceneSectionHeader(title = stringResource(R.string.privilege_section_mode))

            TierCard(
                title = stringResource(R.string.privilege_tier_root),
                description = stringResource(R.string.privilege_tier_root_desc),
                status = if (rootAvailable) stringResource(R.string.privilege_status_available) else stringResource(R.string.privilege_status_unavailable),
                selected = selectedTier == PrivilegeTier.ROOT,
                enabled = true,
                onSelect = {
                    PrivilegeManager.setTier(context, PrivilegeTier.ROOT)
                    selectedTier = PrivilegeTier.ROOT
                    Toast.makeText(context, R.string.privilege_switched, Toast.LENGTH_SHORT).show()
                    revision++
                }
            )

            TierCard(
                title = stringResource(R.string.privilege_tier_shizuku),
                description = stringResource(R.string.privilege_tier_shizuku_desc),
                status = when {
                    !shizukuInstalled -> stringResource(R.string.privilege_status_not_installed)
                    !shizukuAvailable -> stringResource(R.string.privilege_status_service_offline)
                    !shizukuGranted -> stringResource(R.string.privilege_status_permission_denied)
                    else -> stringResource(R.string.privilege_status_ready)
                },
                selected = selectedTier == PrivilegeTier.SHIZUKU,
                enabled = true,
                onSelect = {
                    PrivilegeManager.setTier(context, PrivilegeTier.SHIZUKU)
                    selectedTier = PrivilegeTier.SHIZUKU
                    if (!shizukuGranted && shizukuAvailable) {
                        PrivilegeManager.requestShizukuPermission()
                    }
                    Toast.makeText(context, R.string.privilege_switched, Toast.LENGTH_SHORT).show()
                    revision++
                }
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(SceneSpacing.sm)) {
                    if (!shizukuInstalled) {
                        OutlinedButton(onClick = {
                            Toast.makeText(context, R.string.privilege_requires_shizuku_app, Toast.LENGTH_LONG).show()
                        }) {
                            Text(stringResource(R.string.privilege_action_install_shizuku))
                        }
                    } else {
                        if (!shizukuGranted) {
                            Button(onClick = {
                                PrivilegeManager.refreshShizuku()
                                if (PrivilegeManager.shizukuAvailable) {
                                    PrivilegeManager.requestShizukuPermission()
                                } else {
                                    Toast.makeText(context, R.string.privilege_status_service_offline, Toast.LENGTH_LONG).show()
                                }
                            }) {
                                Text(stringResource(R.string.privilege_action_request_permission))
                            }
                        }
                        OutlinedButton(onClick = {
                            if (!PrivilegeManager.openShizukuApp(context)) {
                                Toast.makeText(context, R.string.privilege_requires_shizuku_app, Toast.LENGTH_LONG).show()
                            }
                        }) {
                            Text(stringResource(R.string.privilege_action_open_shizuku))
                        }
                    }
                }
            }

            TierCard(
                title = stringResource(R.string.privilege_tier_non_root),
                description = stringResource(R.string.privilege_tier_non_root_desc),
                status = stringResource(R.string.privilege_status_always_available),
                selected = selectedTier == PrivilegeTier.NON_ROOT,
                enabled = true,
                onSelect = {
                    PrivilegeManager.setTier(context, PrivilegeTier.NON_ROOT)
                    selectedTier = PrivilegeTier.NON_ROOT
                    Toast.makeText(context, R.string.privilege_switched, Toast.LENGTH_SHORT).show()
                    revision++
                }
            )

            Spacer(modifier = Modifier.height(SceneSpacing.md))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        R.string.privilege_effective,
                        stringResource(effectiveTierLabel(PrivilegeManager.effectiveTier))
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { revision++ }, enabled = !busy) {
                    Text(stringResource(R.string.privilege_action_refresh))
                }
            }
        }
    }

    @Composable
    private fun TierCard(
        title: String,
        description: String,
        status: String,
        selected: Boolean,
        enabled: Boolean,
        onSelect: () -> Unit,
        extra: @Composable (() -> Unit)? = null
    ) {
        SceneCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = SceneSpacing.sm)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = selected, enabled = enabled, onClick = onSelect),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
                Column(modifier = Modifier.padding(start = SceneSpacing.xs)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (extra != null) {
                Spacer(modifier = Modifier.height(SceneSpacing.sm))
                extra()
            }
        }
    }

    private fun effectiveTierLabel(tier: PrivilegeTier): Int {
        return when (tier) {
            PrivilegeTier.ROOT -> R.string.privilege_tier_root
            PrivilegeTier.SHIZUKU -> R.string.privilege_tier_shizuku
            PrivilegeTier.NON_ROOT -> R.string.privilege_tier_non_root
        }
    }
}
