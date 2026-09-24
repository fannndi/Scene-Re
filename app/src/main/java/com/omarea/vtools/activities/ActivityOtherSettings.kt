package com.omarea.vtools.activities

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Switch
import androidx.core.content.PermissionChecker
import androidx.lifecycle.lifecycleScope
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.ui.DialogHelper
import com.omarea.data.EventBus
import com.omarea.data.EventType
import com.omarea.shell_utils.AppErrorLogcatUtils
import com.omarea.store.SpfConfig
import com.omarea.utils.CommonCmds
import com.omarea.utils.SceneLog
import com.omarea.vtools.R
import com.omarea.vtools.databinding.ActivityOtherSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        // Edge-to-edge (targetSdk 36) has no opt-out, so the shared app bar must
        // absorb the status-bar / cutout inset itself.
        applyAppBarInsets()

        setBackArrow()

        binding.settingsDisableSelinux.setOnClickListener {
            val enabled = binding.settingsDisableSelinux.isChecked
            // KeepShellPublic.doCmdSync blocks for tens to hundreds of milliseconds
            // and spawns a shell process, so it must not run on the main thread.
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    if (enabled) {
                        KeepShellPublic.doCmdSync(CommonCmds.DisableSELinux)
                    } else {
                        KeepShellPublic.doCmdSync(CommonCmds.ResumeSELinux)
                    }
                }
                if (enabled) {
                    myHandler.postDelayed({
                        spf.edit().putBoolean(SpfConfig.GLOBAL_SPF_DISABLE_ENFORCE, enabled).apply()
                    }, 10000)
                } else {
                    spf.edit().putBoolean(SpfConfig.GLOBAL_SPF_DISABLE_ENFORCE, enabled).apply()
                }
            }
        }
        binding.settingsLogcat.setOnClickListener {
            // catLogInfo() shells out to `logcat -d`, which can take hundreds of
            // milliseconds to a few seconds, so it is kept off the main thread.
            lifecycleScope.launch {
                val log = withContext(Dispatchers.IO) { AppErrorLogcatUtils().catLogInfo() }
                binding.settingsLogContent.visibility = View.VISIBLE
                binding.settingsLogContent.setText(log)
                binding.settingsLogContent.setSelection(0, log.length)
            }
        }

        binding.settingsDebugLayer.isChecked = spf.getBoolean(SpfConfig.GLOBAL_SPF_SCENE_LOG, false)
        binding.settingsDebugLayer.setOnClickListener {
            val enabled = (it as Switch).isChecked
            spf.edit().putBoolean(SpfConfig.GLOBAL_SPF_SCENE_LOG, enabled).apply()
            // The same switch also drives file logging, so a bug report gathered later
            // contains the events that led up to the problem.
            SceneLog.setFileLoggingEnabled(enabled)

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

        binding.settingsBlackNotification.isChecked = spf.getBoolean(SpfConfig.GLOBAL_NIGHT_BLACK_NOTIFICATION, false)
        binding.settingsBlackNotification.setOnClickListener {
            spf.edit().putBoolean(SpfConfig.GLOBAL_NIGHT_BLACK_NOTIFICATION, (it as Switch).isChecked).apply()
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
