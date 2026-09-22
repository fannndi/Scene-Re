package com.omarea.vtools.setup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.omarea.Scene
import com.omarea.common.shell.KeepShellPublic
import com.omarea.library.permissions.NotificationListener
import com.omarea.permissions.BatteryOptimization
import com.omarea.permissions.WriteSettings
import com.omarea.store.SpfConfig
import com.omarea.utils.AccessibleServiceHelper
import com.omarea.vtools.R
import com.omarea.vtools.activities.ActivityBase
import com.omarea.vtools.activities.ActivityMain
import com.omarea.vtools.activities.ActivityPrivilege
import com.omarea.vtools.privilege.PrivilegeManager
import com.omarea.vtools.privilege.PrivilegeTier
import com.omarea.vtools.privilege.ShizukuHealth
import com.omarea.vtools.privilege.ShizukuHealthCheck
import com.omarea.vtools.privilege.ShizukuHealthState
import com.omarea.vtools.ui.components.SceneCard
import com.omarea.vtools.ui.components.SceneSectionHeader
import com.omarea.vtools.ui.theme.SceneSpacing
import com.omarea.vtools.ui.theme.SceneTheme

/**
 * First-run setup: privilege mode, Shizuku health and the permissions the app needs to work.
 *
 * Every row re-reads its state when the activity resumes, so returning from a system settings
 * screen immediately shows the new state. Nothing here is mandatory to finish the setup; the
 * "Skip" action marks it as completed and continues to the main screen.
 */
class ActivitySetup : ActivityBase() {
    private val refreshTick = mutableIntStateOf(0)

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
                SetupScreen(refreshTick.intValue)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning from a system settings screen must refresh every status row.
        refreshTick.intValue++
    }

    @Composable
    private fun SetupScreen(tick: Int) {
        val context = this
        // Reading the tick makes the whole list re-evaluate after onResume.
        val privilegeReady = tick.let { PrivilegeManager.isPrivileged }
        val shizukuHealth = tick.let { ShizukuHealthCheck.check(context) }
        val writeSettings = tick.let { WriteSettings().checkPermission(context) }
        val batteryExempt = tick.let { BatteryOptimization().isExempt(context) }
        val accessibility = tick.let { AccessibleServiceHelper().serviceRunning(context) }
        val notificationListener = tick.let { NotificationListener().getPermission(context) }
        val overlay = tick.let { canDrawOverlays(context) }
        val storage = tick.let { hasStoragePermission(context) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(SceneSpacing.lg)
        ) {
            Text(
                text = stringResource(R.string.setup_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(SceneSpacing.xs))
            Text(
                text = stringResource(R.string.setup_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(SceneSpacing.md))

            SceneSectionHeader(title = stringResource(R.string.setup_section_privilege))
            SetupRow(
                title = stringResource(R.string.privilege_title),
                status = stringResource(R.string.setup_privilege_status, PrivilegeManager.effectiveTier.storageValue),
                ok = privilegeReady,
                action = stringResource(R.string.setup_action_change),
                onAction = { startActivity(Intent(context, ActivityPrivilege::class.java)) }
            )
            if (PrivilegeManager.tier == PrivilegeTier.SHIZUKU) {
                SetupRow(
                    title = stringResource(R.string.setup_shizuku_health),
                    status = shizukuHealth.message,
                    ok = shizukuHealth.healthy,
                    action = stringResource(actionForHealth(shizukuHealth)),
                    onAction = { fixShizuku(shizukuHealth) }
                )
            }

            SceneSectionHeader(title = stringResource(R.string.setup_section_permissions))
            SetupRow(
                title = stringResource(R.string.setup_write_settings),
                status = statusText(writeSettings),
                ok = writeSettings,
                action = stringResource(R.string.setup_action_grant),
                onAction = { fixWriteSettings() }
            )
            SetupRow(
                title = stringResource(R.string.setup_battery),
                status = statusText(batteryExempt),
                ok = batteryExempt,
                action = stringResource(R.string.setup_action_grant),
                onAction = { fixBattery() }
            )
            SetupRow(
                title = stringResource(R.string.setup_accessibility),
                status = statusText(accessibility),
                ok = accessibility,
                action = stringResource(R.string.setup_action_grant),
                onAction = { fixAccessibility() }
            )
            SetupRow(
                title = stringResource(R.string.setup_notification_listener),
                status = statusText(notificationListener),
                ok = notificationListener,
                action = stringResource(R.string.setup_action_grant),
                onAction = { NotificationListener().setPermission(context) }
            )
            SetupRow(
                title = stringResource(R.string.setup_overlay),
                status = statusText(overlay),
                ok = overlay,
                action = stringResource(R.string.setup_action_grant),
                onAction = { requestOverlay() }
            )
            SetupRow(
                title = stringResource(R.string.setup_storage),
                status = statusText(storage),
                ok = storage,
                action = stringResource(R.string.setup_action_grant),
                onAction = { requestStorage() }
            )

            Spacer(modifier = Modifier.height(SceneSpacing.lg))
            Button(
                onClick = { completeSetup() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.setup_done))
            }
            TextButton(
                onClick = { completeSetup() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.setup_skip))
            }
            Spacer(modifier = Modifier.height(SceneSpacing.lg))
        }
    }

    @Composable
    private fun SetupRow(
        title: String,
        status: String,
        ok: Boolean,
        action: String,
        onAction: () -> Unit
    ) {
        SceneCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = SceneSpacing.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
                if (!ok) {
                    TextButton(onClick = onAction) {
                        Text(action)
                    }
                }
            }
        }
    }

    @Composable
    private fun statusText(ok: Boolean): String {
        return stringResource(if (ok) R.string.setup_status_granted else R.string.setup_status_missing)
    }

    private fun actionForHealth(health: ShizukuHealth): Int {
        return when (health.state) {
            ShizukuHealthState.PERMISSION_DENIED -> R.string.privilege_action_request_permission
            ShizukuHealthState.SHELL_UNAVAILABLE -> R.string.setup_action_retry
            else -> R.string.privilege_action_open_shizuku
        }
    }

    private fun fixShizuku(health: ShizukuHealth) {
        when (health.state) {
            ShizukuHealthState.PERMISSION_DENIED -> PrivilegeManager.requestShizukuPermission()
            ShizukuHealthState.SHELL_UNAVAILABLE -> PrivilegeManager.bindShellService()
            else -> if (!PrivilegeManager.openShizukuApp(this)) {
                Toast.makeText(this, R.string.privilege_requires_shizuku_app, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun fixWriteSettings() {
        val context = this
        if (PrivilegeManager.isPrivileged) {
            runOffMain {
                if (!WriteSettings().setPermissionByRoot(context)) {
                    Scene.post { WriteSettings().requestPermission(context) }
                }
            }
        } else {
            WriteSettings().requestPermission(this)
        }
    }

    private fun fixBattery() {
        val context = this
        if (PrivilegeManager.isPrivileged) {
            runOffMain {
                if (!BatteryOptimization().grantByShell(context)) {
                    Scene.post { BatteryOptimization().requestExemption(context) }
                }
            }
        } else {
            BatteryOptimization().requestExemption(this)
        }
    }

    private fun fixAccessibility() {
        val context = this
        if (PrivilegeManager.isPrivileged) {
            runOffMain {
                val helper = AccessibleServiceHelper()
                if (!helper.serviceRunning(context)) {
                    helper.startSceneModeService(context)
                }
            }
        } else {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (ex: Exception) {
                Toast.makeText(this, R.string.setup_accessibility_manual, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun requestOverlay() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.fromParts("package", packageName, null)
            )
            startActivity(intent)
        } catch (ex: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (ex2: Exception) {
                Toast.makeText(this, R.string.setup_overlay_manual, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun requestStorage() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ),
            0x21
        )
    }

    private fun canDrawOverlays(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    }

    private fun hasStoragePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun runOffMain(block: () -> Unit) {
        Thread(block).start()
    }

    private fun completeSetup() {
        Scene.globalConfig.edit().putBoolean(SpfConfig.GLOBAL_SPF_SETUP_COMPLETED, true).apply()
        KeepShellPublic.tryExit()
        val intent = Intent(this, ActivityMain::class.java)
        startActivity(intent)
        finish()
    }
}
