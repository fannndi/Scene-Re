package com.omarea.ui.activity

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.ui.DialogHelper
import com.omarea.runtime.BatterySaverMode
import com.omarea.runtime.ConfigBackup
import com.omarea.runtime.SceneCleanup
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

        binding.settingsAutoExit.isChecked = spf.getBoolean(SpfConfig.GLOBAL_SPF_AUTO_EXIT, true)
        binding.settingsAutoExit.setOnClickListener {
            spf.edit().putBoolean(SpfConfig.GLOBAL_SPF_AUTO_EXIT, (it as Switch).isChecked).apply()
        }

        binding.settingsSaverOverlayEnabled.isChecked =
            spf.getBoolean(SpfConfig.GLOBAL_SPF_SAVER_OVERLAY_ENABLED, SpfConfig.GLOBAL_SPF_SAVER_OVERLAY_DEFAULT)
        binding.settingsSaverOverlayEnabled.setOnClickListener {
            spf.edit()
                .putBoolean(SpfConfig.GLOBAL_SPF_SAVER_OVERLAY_ENABLED, (it as Switch).isChecked)
                .apply()
            // Turning it off mid-overlay must undo the overlay immediately.
            BatterySaverMode.evaluate(this)
        }



        binding.settingsBootDelay.isChecked = spf.getBoolean(SpfConfig.GLOBAL_SPF_START_DELAY, false)
        binding.settingsBootDelay.setOnClickListener {
            spf.edit().putBoolean(SpfConfig.GLOBAL_SPF_START_DELAY, (it as Switch).isChecked).apply()
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




    override fun onDestroy() {
        super.onDestroy()

        spf.edit().putBoolean(SpfConfig.GLOBAL_SPF_DISABLE_ENFORCE, binding.settingsDisableSelinux.isChecked).apply()
    }

    public override fun onPause() {
        super.onPause()
    }
}
