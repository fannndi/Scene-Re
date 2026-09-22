package com.omarea.vtools.kernel

import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.omarea.vtools.R
import com.omarea.vtools.activities.ActivityBase
import com.omarea.vtools.privilege.PrivilegeManager
import com.omarea.vtools.ui.components.SceneChip
import com.omarea.vtools.ui.components.SceneTone
import com.omarea.vtools.ui.theme.SceneSpacing
import com.omarea.vtools.ui.theme.SceneTheme
import kotlinx.coroutines.launch

/**
 * Kernel manager: Device / Battery / SoC / Parameters / Profiles.
 *
 * Ported from RvKernel-Manager (GPL-3.0, Rve27) and re-implemented on top of Scene's shell backend,
 * so every read and write follows the active privilege tier. The screen is always reachable: without
 * root the read-only parts still work and every write control is disabled with a "Root required"
 * chip instead of being hidden.
 */
class ActivityKernel : ActivityBase() {
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
                KernelManagerScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KernelManagerScreen(onBack: () -> Unit) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val hasRoot = PrivilegeManager.hasRootAccess
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val tabs = listOf(
        R.string.kernel_tab_device,
        R.string.kernel_tab_battery,
        R.string.kernel_tab_soc,
        R.string.kernel_tab_parameters,
        R.string.kernel_tab_profiles
    )

    val onMessage: (String) -> Unit = { message ->
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.kernel_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                },
                actions = {
                    TextButton(onClick = { refreshKey++ }) {
                        Text(stringResource(R.string.kernel_refresh))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!hasRoot) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SceneSpacing.lg, vertical = SceneSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SceneChip(
                        label = stringResource(R.string.kernel_root_required),
                        tone = SceneTone.WARNING
                    )
                    Spacer(modifier = Modifier.width(SceneSpacing.sm))
                    Text(
                        text = stringResource(R.string.kernel_root_required_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier.fillMaxWidth()
            ) {
                tabs.forEachIndexed { index, titleRes ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = stringResource(titleRes),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1
                            )
                        }
                    )
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when (selectedTab) {
                    0 -> KernelDeviceScreen(refreshKey = refreshKey)
                    1 -> KernelBatteryScreen(refreshKey = refreshKey, hasRoot = hasRoot, onMessage = onMessage)
                    2 -> KernelSoCScreen(refreshKey = refreshKey, hasRoot = hasRoot, onMessage = onMessage)
                    3 -> KernelParametersScreen(refreshKey = refreshKey, hasRoot = hasRoot, onMessage = onMessage)
                    4 -> KernelProfilesScreen(refreshKey = refreshKey, hasRoot = hasRoot, onMessage = onMessage)
                }
            }
        }
    }
}
