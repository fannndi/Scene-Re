package com.omarea.ui.activity

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Switch
import android.widget.Toast
import androidx.core.content.PermissionChecker
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.ui.DialogHelper
import com.omarea.data.EventBus
import com.omarea.data.EventType
import com.omarea.runtime.BypassCharging
import com.omarea.runtime.ConfigBackup
import com.omarea.runtime.DisplayRestart
import com.omarea.runtime.DndController
import com.omarea.runtime.GamePreload
import com.omarea.runtime.ProcessPriority
import com.omarea.runtime.SceneCleanup
import com.omarea.runtime.SystemTools
import com.omarea.util.AppErrorLogcatUtils
import com.omarea.util.CheckRootStatus
import com.omarea.data.SpfConfig
import com.omarea.util.CommonCmds
import com.omarea.vtools.R
import com.omarea.vtools.databinding.ActivityOtherSettingsBinding

class ActivityOtherSettings : ActivityBase() {
    private lateinit var spf: SharedPreferences
    private var myHandler = Handler(Looper.getMainLooper())
    private lateinit var binding: ActivityOtherSettingsBinding

    override fun onPostResume() {
        super.onPostResume()
        delegate.onPostResume()

        binding.settingsDisableSelinux.isChecked = spf.getBoolean(SpfConfig.GLOBAL_SPF_DISABLE_ENFORCE, false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        spf = getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
        super.onCreate(savedInstanceState)
        binding = ActivityOtherSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setBackArrow()

        binding.settingsDisableSelinux.setOnClickListener {
            if (binding.settingsDisableSelinux.isChecked) {
                KeepShellPublic.doCmdSync(CommonCmds.DisableSELinux)
                myHandler.postDelayed({
                    spf.edit().putBoolean(SpfConfig.GLOBAL_SPF_DISABLE_ENFORCE, binding.settingsDisableSelinux.isChecked).apply()
                }, 10000)
            } else {
                KeepShellPublic.doCmdSync(CommonCmds.ResumeSELinux)
                spf.edit().putBoolean(SpfConfig.GLOBAL_SPF_DISABLE_ENFORCE, binding.settingsDisableSelinux.isChecked).apply()
            }
        }
        binding.settingsLogcat.setOnClickListener {
            val log = AppErrorLogcatUtils().catLogInfo()
            binding.settingsLogContent.visibility = View.VISIBLE
            binding.settingsLogContent.setText(log)
            binding.settingsLogContent.setSelection(0, log.length)
        }

        binding.settingsCleanupScene.setOnClickListener {
            if (!CheckRootStatus.isAvailable()) {
                Toast.makeText(this, R.string.toast_root_missing, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            DialogHelper.confirm(
                this,
                getString(R.string.settings_uninstall_cleanup),
                getString(R.string.settings_uninstall_cleanup_confirm)
            ) {
                Thread {
                    val result = SceneCleanup.cleanupNow(this)
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            getString(R.string.settings_uninstall_cleanup_done) + " ($result)",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }.start()
            }
        }

        binding.settingsDebugLayer.isChecked = spf.getBoolean(SpfConfig.GLOBAL_SPF_SCENE_LOG, false)
        binding.settingsDebugLayer.setOnClickListener {
            spf.edit().putBoolean(SpfConfig.GLOBAL_SPF_SCENE_LOG, (it as Switch).isChecked).apply()

            EventBus.publish(EventType.SERVICE_DEBUG)
        }

        binding.settingsHelpIcon.isChecked = spf.getBoolean(SpfConfig.GLOBAL_SPF_HELP_ICON, true)
        binding.settingsHelpIcon.setOnClickListener {
            spf.edit().putBoolean(SpfConfig.GLOBAL_SPF_HELP_ICON, (it as Switch).isChecked).apply()
        }

        binding.settingsAutoExit.isChecked = spf.getBoolean(SpfConfig.GLOBAL_SPF_AUTO_EXIT, true)
        binding.settingsAutoExit.setOnClickListener {
            spf.edit().putBoolean(SpfConfig.GLOBAL_SPF_AUTO_EXIT, (it as Switch).isChecked).apply()
        }

        binding.settingsDndAppMode.isChecked = DndController.isEnabled(this)
        binding.settingsDndAppMode.setOnClickListener {
            val checked = (it as Switch).isChecked
            spf.edit().putBoolean(SpfConfig.GLOBAL_SPF_DND_APP_MODE, checked).apply()
            if (checked && !DndController.isGranted(this)) {
                runCatching {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                }
            }
            // Turning it off restores any active DND immediately.
            DndController.evaluate(this, false)
            updateDndSummary()
        }
        updateDndSummary()

        binding.settingsGamePriority.isChecked = ProcessPriority.isEnabled(this)
        binding.settingsGamePriority.setOnClickListener {
            spf.edit().putBoolean(SpfConfig.GLOBAL_SPF_GAME_PRIORITY, (it as Switch).isChecked).apply()
        }

        binding.settingsGamePreload.isChecked = GamePreload.isEnabled(this)
        binding.settingsGamePreload.setOnClickListener {
            spf.edit().putBoolean(SpfConfig.GLOBAL_SPF_GAME_PRELOAD, (it as Switch).isChecked).apply()
        }

        binding.settingsDisplayRestart.isChecked = DisplayRestart.isEnabled(this)
        binding.settingsDisplayRestart.setOnClickListener {
            spf.edit().putBoolean(SpfConfig.GLOBAL_SPF_DISPLAY_RESTART, (it as Switch).isChecked).apply()
        }

        binding.settingsBypassCharge.isChecked = BypassCharging.isEnabled(this)
        binding.settingsBypassCharge.setOnClickListener {
            BypassCharging.setEnabled(this, (it as Switch).isChecked)
            updateBypassSummary()
        }
        updateBypassSummary()
        binding.settingsBypassChargeDesc.setOnClickListener {
            if (!BypassCharging.isEnabled(this)) return@setOnClickListener
            val next = when (BypassCharging.threshold(this)) {
                60 -> 70
                70 -> 80
                80 -> 90
                else -> 60
            }
            BypassCharging.setThreshold(this, next)
            updateBypassSummary()
        }

        binding.settingsBypassChargeMode.setOnClickListener {
            if (!BypassCharging.isEnabled(this)) return@setOnClickListener
            val next = when (BypassCharging.mode(this)) {
                BypassCharging.MODE_AUTO -> BypassCharging.MODE_BYPASS
                BypassCharging.MODE_BYPASS -> BypassCharging.MODE_PAUSE
                else -> BypassCharging.MODE_AUTO
            }
            BypassCharging.setMode(this, next)
            updateBypassSummary()
        }

        binding.settingsJitCompile.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_jit_compile)
                .setMessage(R.string.settings_jit_compile_desc)
                .setPositiveButton(android.R.string.ok) { _, _ -> SystemTools.jitCompile(this) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        binding.settingsFstrim.setOnClickListener {
            SystemTools.fstrim(this)
        }

        binding.settingsBackup.setOnClickListener {
            Thread {
                val result = ConfigBackup.backup(this)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(
                            if (result.ok) R.string.settings_backup_done else R.string.settings_backup_failed,
                            result.message
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }.start()
        }

        binding.settingsRestore.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_restore)
                .setMessage(R.string.settings_backup_desc)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    Thread {
                        val result = ConfigBackup.restoreLatest(this)
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                getString(
                                    if (result.ok) R.string.settings_restore_done else R.string.settings_backup_failed,
                                    result.message
                                ),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }.start()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        binding.settingsBlackNotification.isChecked = spf.getBoolean(SpfConfig.GLOBAL_NIGHT_BLACK_NOTIFICATION, false)
        binding.settingsBlackNotification.setOnClickListener {
            spf.edit().putBoolean(SpfConfig.GLOBAL_NIGHT_BLACK_NOTIFICATION, (it as Switch).isChecked).apply()
        }
    }

    private fun updateBypassSummary() {
        binding.settingsBypassChargeDesc.text = if (BypassCharging.isEnabled(this)) {
            getString(R.string.settings_bypass_charge_on, BypassCharging.threshold(this))
        } else {
            getString(R.string.settings_bypass_charge_desc)
        }
        binding.settingsBypassChargeMode.text = getString(
            when (BypassCharging.mode(this)) {
                BypassCharging.MODE_BYPASS -> R.string.settings_bypass_charge_mode_bypass
                BypassCharging.MODE_PAUSE -> R.string.settings_bypass_charge_mode_pause
                else -> R.string.settings_bypass_charge_mode_auto
            }
        )
    }

    private fun updateDndSummary() {
        val needsGrant = DndController.isEnabled(this) && !DndController.isGranted(this)
        binding.settingsDndAppModeDesc.text = if (needsGrant) {
            getString(R.string.settings_dnd_app_mode_grant)
        } else {
            getString(R.string.settings_dnd_app_mode_desc)
        }
        binding.settingsDndAppModeDesc.setOnClickListener {
            if (needsGrant) {
                runCatching {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                }
            }
        }
    }

    private fun checkPermission(context: Context, permission: String): Boolean = PermissionChecker.checkSelfPermission(context, permission) == PermissionChecker.PERMISSION_GRANTED

    private fun hasRWPermission(): Boolean {
        return checkPermission(this.applicationContext, Manifest.permission.READ_EXTERNAL_STORAGE)
                &&
                checkPermission(this.applicationContext, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    fun onThemeClick(view: View) {
        val tag = view.tag.toString().toInt()
        if (tag == 10 && spf.getInt(SpfConfig.GLOBAL_SPF_THEME, 1) == 10) {
            spf.edit().remove(SpfConfig.GLOBAL_SPF_THEME).apply()
            this.recreate()
        } else {
            if (tag == 10 && !hasRWPermission()) {
                DialogHelper.helpInfo(view.context, "", getString(R.string.wallpaper_rw_permission))
                (view as Switch).isChecked = false
            } else {
                spf.edit().putInt(SpfConfig.GLOBAL_SPF_THEME, tag).apply()
                this.recreate()
            }
        }

    }

    override fun onDestroy() {
        super.onDestroy()

        spf.edit().putBoolean(SpfConfig.GLOBAL_SPF_DISABLE_ENFORCE, binding.settingsDisableSelinux.isChecked).apply()
    }

    public override fun onPause() {
        super.onPause()
    }
}
