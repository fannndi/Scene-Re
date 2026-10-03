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
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.PermissionChecker
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.ui.DialogHelper
import com.omarea.data.EventBus
import com.omarea.data.EventType
import com.omarea.runtime.BatterySaverMode
import com.omarea.runtime.BypassCharging
import com.omarea.runtime.ConfigBackup
import com.omarea.runtime.DisplayRestart
import com.omarea.runtime.DndController
import com.omarea.runtime.GamePreload
import com.omarea.runtime.IrqAffinity
import com.omarea.runtime.KernelCrashGuard
import com.omarea.runtime.LoggingReduction
import com.omarea.runtime.ProcessPriority
import com.omarea.runtime.RootForegroundWatch
import com.omarea.runtime.SceneCleanup
import com.omarea.runtime.SfFramePacing
import com.omarea.runtime.SystemTools
import com.omarea.util.AppErrorLogcatUtils
import com.omarea.util.CheckRootStatus
import com.omarea.data.SpfConfig
import com.omarea.util.CommonCmds
import com.omarea.vtools.R
import com.omarea.vtools.databinding.ActivityOtherSettingsBinding
import java.util.Locale

class ActivityOtherSettings : ActivityBase() {
    /** Accepted Wi-Fi MAC form: six hex octets separated by colons. */
    private val MAC_PATTERN = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
    /** Original visibilities, so an emptied filter restores exactly that. */
    private val originalVisibility = HashMap<View, Int>()
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
        title = getString(R.string.settings_screen_title)
        bindFilter()
        binding.settingsAbout.setOnClickListener {
            startActivity(Intent(this, ActivityAbout::class.java))
        }

        binding.settingsDisableSelinux.setOnClickListener {
            if (binding.settingsDisableSelinux.isChecked) {
                // Disabling MAC enforcement is not one tap away (hard rules
                // 16 + safety): confirm first, revert when cancelled.
                if (!CheckRootStatus.isAvailable()) {
                    Toast.makeText(this, R.string.settings_tools_no_root, Toast.LENGTH_LONG).show()
                    binding.settingsDisableSelinux.isChecked = false
                    return@setOnClickListener
                }
                DialogHelper.warning(
                    this,
                    getString(R.string.settings_selinux_disable_title),
                    getString(R.string.settings_selinux_disable_desc),
                    Runnable {
                        KeepShellPublic.doCmdSync(CommonCmds.DisableSELinux)
                        myHandler.postDelayed({
                            spf.edit().putBoolean(SpfConfig.GLOBAL_SPF_DISABLE_ENFORCE, true).apply()
                        }, 10000)
                    },
                    Runnable { binding.settingsDisableSelinux.isChecked = false }
                )
            } else {
                KeepShellPublic.doCmdSync(CommonCmds.ResumeSELinux)
                spf.edit().putBoolean(SpfConfig.GLOBAL_SPF_DISABLE_ENFORCE, false).apply()
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
                getString(R.string.settings_uninstall_cleanup_confirm),
                Runnable {
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
                },
                null
            )
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

        binding.settingsSaverOverlayEnabled.isChecked =
            spf.getBoolean(SpfConfig.GLOBAL_SPF_SAVER_OVERLAY_ENABLED, SpfConfig.GLOBAL_SPF_SAVER_OVERLAY_DEFAULT)
        binding.settingsSaverOverlayEnabled.setOnClickListener {
            spf.edit()
                .putBoolean(SpfConfig.GLOBAL_SPF_SAVER_OVERLAY_ENABLED, (it as Switch).isChecked)
                .apply()
            // Turning it off mid-overlay must undo the overlay immediately.
            BatterySaverMode.evaluate(this)
        }

        binding.settingsGamePriority.isChecked = ProcessPriority.isEnabled(this)
        binding.settingsGamePriority.setOnClickListener {
            spf.edit().putBoolean(SpfConfig.GLOBAL_SPF_GAME_PRIORITY, (it as Switch).isChecked).apply()
        }

        binding.settingsGamePreload.isChecked = GamePreload.isEnabled(this)
        binding.settingsGamePreload.setOnClickListener {
            spf.edit().putBoolean(SpfConfig.GLOBAL_SPF_GAME_PRELOAD, (it as Switch).isChecked).apply()
        }
        binding.settingsGamePreloadMb.text = getString(R.string.settings_game_preload_mb, GamePreload.budgetMb(this))
        binding.settingsGamePreloadMb.setOnClickListener { showPreloadBudgetDialog() }

        binding.settingsDisplayRestart.isChecked = DisplayRestart.isEnabled(this)
        binding.settingsDisplayRestart.setOnClickListener {
            spf.edit().putBoolean(SpfConfig.GLOBAL_SPF_DISPLAY_RESTART, (it as Switch).isChecked).apply()
        }

        binding.settingsReduceLogging.isChecked = LoggingReduction.isEnabled(this)
        binding.settingsReduceLogging.setOnClickListener {
            val checked = (it as Switch).isChecked
            if (checked) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.settings_reduce_logging)
                    .setMessage(R.string.settings_reduce_logging_desc)
                    .setPositiveButton(android.R.string.ok) { _, _ -> LoggingReduction.setEnabled(this, true) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> binding.settingsReduceLogging.isChecked = false }
                    .show()
            } else {
                LoggingReduction.setEnabled(this, false)
            }
        }

        binding.settingsKernelCrashGuard.isChecked = KernelCrashGuard.isEnabled(this)
        binding.settingsKernelCrashGuard.setOnClickListener {
            val checked = (it as Switch).isChecked
            if (checked) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.settings_kernel_crash_guard)
                    .setMessage(R.string.settings_kernel_crash_guard_desc)
                    .setPositiveButton(android.R.string.ok) { _, _ -> KernelCrashGuard.setEnabled(this, true) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> binding.settingsKernelCrashGuard.isChecked = false }
                    .show()
            } else {
                KernelCrashGuard.setEnabled(this, false)
            }
        }

        binding.settingsSfPacing.isChecked = SfFramePacing.isEnabled(this)
        binding.settingsSfPacing.setOnClickListener {
            val checked = (it as Switch).isChecked
            if (checked) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.settings_sf_pacing)
                    .setMessage(R.string.settings_sf_pacing_desc)
                    .setPositiveButton(android.R.string.ok) { _, _ -> SfFramePacing.setEnabled(this, true) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> binding.settingsSfPacing.isChecked = false }
                    .show()
            } else {
                SfFramePacing.setEnabled(this, false)
            }
        }

        binding.settingsRootWatch.isChecked = RootForegroundWatch.isEnabled(this)
        binding.settingsRootWatch.setOnClickListener {
            val checked = (it as Switch).isChecked
            if (checked) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.settings_root_watch)
                    .setMessage(R.string.settings_root_watch_desc)
                    .setPositiveButton(android.R.string.ok) { _, _ -> RootForegroundWatch.setEnabled(this, true) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> binding.settingsRootWatch.isChecked = false }
                    .show()
            } else {
                RootForegroundWatch.setEnabled(this, false)
            }
        }

        binding.settingsIrqAffinity.isChecked = IrqAffinity.isEnabled(this)
        binding.settingsIrqAffinity.setOnClickListener {
            val checked = (it as Switch).isChecked
            if (checked) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.settings_irq_affinity)
                    .setMessage(R.string.settings_irq_affinity_desc)
                    .setPositiveButton(android.R.string.ok) { _, _ -> IrqAffinity.setEnabled(this, true) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> binding.settingsIrqAffinity.isChecked = false }
                    .show()
            } else {
                IrqAffinity.setEnabled(this, false)
            }
        }

        binding.settingsBootDelay.isChecked = spf.getBoolean(SpfConfig.GLOBAL_SPF_START_DELAY, false)
        binding.settingsBootDelay.setOnClickListener {
            spf.edit().putBoolean(SpfConfig.GLOBAL_SPF_START_DELAY, (it as Switch).isChecked).apply()
        }
        binding.settingsWifiMac.setOnClickListener { showMacDialog() }
        binding.settingsWifiMacMode.setOnClickListener { showMacModeDialog() }
        updateMacSummary()

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

    // ------------------------------------------------------------------ filter
    /**
     * Search across the settings cards: rows live in sections (header views
     * tagged "section"), and a section shows when the query hits its header
     * or any of its rows. Empty query restores the snapshot exactly.
     */
    private fun bindFilter() {
        snapshotVisibility(binding.settingsList)
        binding.settingsFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter(s?.toString().orEmpty().trim())
            }
        })
    }

    private fun snapshotVisibility(view: View) {
        originalVisibility[view] = view.visibility
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) snapshotVisibility(view.getChildAt(i))
        }
    }

    private fun textOf(view: View): String = (view as? TextView)?.text?.toString().orEmpty()

    private fun applyFilter(query: String) {
        val root = binding.settingsList
        if (query.isEmpty()) {
            originalVisibility.forEach { (view, visibility) -> view.visibility = visibility }
            return
        }
        val q = query.lowercase(Locale.getDefault())
        for (cardIndex in 0 until root.childCount) {
            val card = root.getChildAt(cardIndex)
            if (card === binding.settingsFilter || card !is ViewGroup) continue

            val children = (0 until card.childCount).map { card.getChildAt(it) }
            val matches = children.map { textOf(it).lowercase(Locale.getDefault()).contains(q) }

            // Sections run from one "section"-tagged header to the next.
            val sections = ArrayList<IntRange>()
            var start = 0
            children.forEachIndexed { index, child ->
                if (child.tag == "section") {
                    if (index > start) sections += start until index
                    start = index
                }
            }
            sections += start until children.size

            val visible = BooleanArray(children.size)
            for (section in sections) {
                if (section.isEmpty() || !section.any { matches[it] }) continue
                // Query hit the header itself → keep the whole section open.
                val showAll = children[section.first].tag == "section" && matches[section.first]
                for (index in section) visible[index] = showAll || matches[index]
            }

            var cardVisible = false
            children.forEachIndexed { index, child ->
                child.visibility = if (visible[index]) {
                    originalVisibility[child] ?: View.VISIBLE
                } else {
                    View.GONE
                }
                if (visible[index]) cardVisible = true
            }
            card.visibility = if (cardVisible) View.VISIBLE else View.GONE
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

    private fun showPreloadBudgetDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.settings_game_preload_mb_hint)
            setText(GamePreload.budgetMb(this@ActivityOtherSettings).toString())
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_game_preload_mb_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = input.text.toString().toIntOrNull()
                if (value == null || value !in 32..2048) {
                    Toast.makeText(this, R.string.settings_game_preload_mb_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    spf.edit().putInt(SpfConfig.GLOBAL_SPF_GAME_PRELOAD_MB, value).apply()
                    binding.settingsGamePreloadMb.text = getString(R.string.settings_game_preload_mb, value)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showMacDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.settings_wifi_mac_hint)
            setText(spf.getString(SpfConfig.GLOBAL_SPF_MAC, "").orEmpty())
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_wifi_mac_title)
            .setMessage(R.string.settings_wifi_mac_desc)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val mac = input.text.toString().trim()
                if (mac.isNotEmpty() && !MAC_PATTERN.matches(mac)) {
                    Toast.makeText(this, R.string.settings_wifi_mac_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    // BootWorker reads both keys; empty MAC disables the write.
                    spf.edit().putString(SpfConfig.GLOBAL_SPF_MAC, mac).apply()
                    updateMacSummary()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showMacModeDialog() {
        val modes = intArrayOf(0, SpfConfig.GLOBAL_SPF_MAC_AUTOCHANGE_MODE_1, SpfConfig.GLOBAL_SPF_MAC_AUTOCHANGE_MODE_2)
        val labels = arrayOf(
            getString(R.string.settings_wifi_mac_mode_off),
            getString(R.string.settings_wifi_mac_mode_1),
            getString(R.string.settings_wifi_mac_mode_2)
        )
        val current = spf.getInt(SpfConfig.GLOBAL_SPF_MAC_AUTOCHANGE_MODE, 0)
        val checked = modes.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_wifi_mac_mode)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                spf.edit().putInt(SpfConfig.GLOBAL_SPF_MAC_AUTOCHANGE_MODE, modes[which]).apply()
                updateMacSummary()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateMacSummary() {
        val mac = spf.getString(SpfConfig.GLOBAL_SPF_MAC, "").orEmpty()
        val mode = spf.getInt(SpfConfig.GLOBAL_SPF_MAC_AUTOCHANGE_MODE, 0)
        binding.settingsWifiMacSummary.text = mac.ifEmpty { getString(R.string.settings_wifi_mac_desc) }
        binding.settingsWifiMacModeSummary.text =
            if (mac.isEmpty() || mode == 0) {
                getString(R.string.settings_wifi_mac_mode_off)
            } else if (mode == SpfConfig.GLOBAL_SPF_MAC_AUTOCHANGE_MODE_1) {
                getString(R.string.settings_wifi_mac_mode_1)
            } else {
                getString(R.string.settings_wifi_mac_mode_2)
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
