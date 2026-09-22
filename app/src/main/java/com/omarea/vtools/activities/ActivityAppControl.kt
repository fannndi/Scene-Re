package com.omarea.vtools.activities

import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.omarea.library.shell.FrameworkAppControl
import com.omarea.vtools.R
import com.omarea.vtools.ui.theme.SceneSpacing
import com.omarea.vtools.ui.theme.SceneTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Framework-level control panel for a single app.
 *
 * Everything on this screen goes through `cmd`, `am`, `pm` and `dumpsys`, so it works both with
 * root and with a Shizuku (shell uid) session. That is what makes it useful on a device without
 * root: battery optimisation, background activity, standby bucket and runtime permissions are all
 * reachable without uid 0.
 *
 * All input validation happens inside [FrameworkAppControl]; this screen only renders state and
 * forwards the user's intent.
 */
class ActivityAppControl : ActivityBase() {

    companion object {
        const val EXTRA_PACKAGE_NAME = "package_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val packageName = intent?.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        if (packageName.isEmpty()) {
            finish()
            return
        }

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
                AppControlScreen(packageName = packageName, onBack = { finish() })
            }
        }
    }
}

/** Everything the screen renders, loaded off the main thread. */
private data class AppControlState(
    val loading: Boolean = true,
    val supported: Boolean = true,
    val dozeWhitelisted: Boolean = false,
    val standbyBucket: FrameworkAppControl.StandbyBucket? = null,
    val runInBackgroundMode: String? = null,
    val permissions: List<FrameworkAppControl.PermissionState> = emptyList()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppControlScreen(packageName: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val applyFailedMessage = stringResource(R.string.app_control_apply_failed)

    var state by remember { mutableStateOf(AppControlState()) }

    fun reload() {
        scope.launch {
            state = state.copy(loading = true)
            state = withContext(Dispatchers.IO) {
                if (!FrameworkAppControl.isSupported()) {
                    return@withContext AppControlState(loading = false, supported = false)
                }
                AppControlState(
                    loading = false,
                    supported = true,
                    dozeWhitelisted = FrameworkAppControl.getDozeState(packageName) ==
                            FrameworkAppControl.DozeState.WHITELISTED,
                    standbyBucket = FrameworkAppControl.getStandbyBucket(packageName),
                    runInBackgroundMode = FrameworkAppControl.getAppOpMode(packageName, "RUN_IN_BACKGROUND"),
                    permissions = FrameworkAppControl.getRuntimePermissions(packageName)
                )
            }
        }
    }

    LaunchedEffect(packageName) {
        reload()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_control_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                },
                actions = {
                    TextButton(onClick = { reload() }) {
                        Text(stringResource(R.string.app_control_refresh))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                !state.supported -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(SceneSpacing.lg),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.app_control_unsupported),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(SceneSpacing.lg),
                        verticalArrangement = Arrangement.spacedBy(SceneSpacing.md)
                    ) {
                        Text(
                            text = packageName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = stringResource(R.string.app_control_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        SectionCard(title = stringResource(R.string.app_control_section_battery)) {
                            SwitchRow(
                                title = stringResource(R.string.app_control_doze),
                                description = stringResource(R.string.app_control_doze_desc),
                                checked = state.dozeWhitelisted,
                                onCheckedChange = { wanted ->
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) {
                                            FrameworkAppControl.setDozeWhitelisted(packageName, wanted)
                                        }
                                        if (ok) {
                                            state = state.copy(dozeWhitelisted = wanted)
                                        } else {
                                            snackbarHostState.showSnackbar(applyFailedMessage)
                                        }
                                    }
                                }
                            )

                            HorizontalDivider()

                            Text(
                                text = stringResource(R.string.app_control_standby),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.app_control_standby_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(SceneSpacing.sm))
                            Row(horizontalArrangement = Arrangement.spacedBy(SceneSpacing.sm)) {
                                FrameworkAppControl.StandbyBucket.values().forEach { bucket ->
                                    FilterChip(
                                        selected = state.standbyBucket == bucket,
                                        onClick = {
                                            scope.launch {
                                                val ok = withContext(Dispatchers.IO) {
                                                    FrameworkAppControl.setStandbyBucket(packageName, bucket)
                                                }
                                                if (ok) {
                                                    state = state.copy(standbyBucket = bucket)
                                                } else {
                                                    snackbarHostState.showSnackbar(applyFailedMessage)
                                                }
                                            }
                                        },
                                        label = { Text(bucketLabel(bucket)) }
                                    )
                                }
                            }
                        }

                        SectionCard(title = stringResource(R.string.app_control_section_appops)) {
                            SwitchRow(
                                title = stringResource(R.string.app_control_op_run_in_background),
                                description = stringResource(R.string.app_control_op_run_in_background_desc),
                                checked = state.runInBackgroundMode == "allow",
                                onCheckedChange = { allowed ->
                                    val mode = if (allowed) "allow" else "ignore"
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) {
                                            FrameworkAppControl.setAppOpMode(packageName, "RUN_IN_BACKGROUND", mode)
                                        }
                                        if (ok) {
                                            state = state.copy(runInBackgroundMode = mode)
                                        } else {
                                            snackbarHostState.showSnackbar(applyFailedMessage)
                                        }
                                    }
                                }
                            )
                        }

                        SectionCard(title = stringResource(R.string.app_control_section_permissions)) {
                            if (state.permissions.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.app_control_no_permissions),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                state.permissions.forEachIndexed { index, permission ->
                                    if (index > 0) {
                                        HorizontalDivider()
                                    }
                                    SwitchRow(
                                        // Show only the short name; the full android.permission
                                        // prefix is noise on a phone screen.
                                        title = permission.name.substringAfterLast('.'),
                                        description = permission.name,
                                        checked = permission.granted,
                                        onCheckedChange = { granted ->
                                            scope.launch {
                                                val ok = withContext(Dispatchers.IO) {
                                                    FrameworkAppControl.setPermission(
                                                        packageName, permission.name, granted
                                                    )
                                                }
                                                if (ok) {
                                                    state = state.copy(
                                                        permissions = state.permissions.map {
                                                            if (it.name == permission.name) {
                                                                it.copy(granted = granted)
                                                            } else it
                                                        }
                                                    )
                                                } else {
                                                    snackbarHostState.showSnackbar(applyFailedMessage)
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun bucketLabel(bucket: FrameworkAppControl.StandbyBucket): String {
    return stringResource(
        when (bucket) {
            FrameworkAppControl.StandbyBucket.ACTIVE -> R.string.app_control_bucket_active
            FrameworkAppControl.StandbyBucket.WORKING_SET -> R.string.app_control_bucket_working_set
            FrameworkAppControl.StandbyBucket.FREQUENT -> R.string.app_control_bucket_frequent
            FrameworkAppControl.StandbyBucket.RARE -> R.string.app_control_bucket_rare
            FrameworkAppControl.StandbyBucket.RESTRICTED -> R.string.app_control_bucket_restricted
        }
    )
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(SceneSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(SceneSpacing.sm)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
