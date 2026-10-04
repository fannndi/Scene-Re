package com.omarea.vtools.activities

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Switch
import android.widget.Toast
import com.omarea.data.EventBus
import com.omarea.data.EventType
import com.omarea.model.SceneConfigInfo
import com.omarea.permissions.WriteSettings
import com.omarea.scene_mode.ModeSwitcher
import com.omarea.store.SceneConfigStore
import com.omarea.store.SpfConfig
import com.omarea.utils.AccessibleServiceHelper
import com.omarea.vtools.R
import com.omarea.vtools.databinding.ActivityAppTuningBinding
import com.omarea.vtools.dialogs.DialogAppOrientation
import com.omarea.vtools.dialogs.DialogAppPowerConfig

/**
 * Per-app tuning: performance mode, brightness, GPS, rotation, monitor and cgroup.
 * Opened by tapping an app in the App scene (App strategy) list.
 */
class ActivityAppTuning : ActivityBase() {
    private lateinit var binding: ActivityAppTuningBinding
    private lateinit var store: SceneConfigStore
    private lateinit var spfPowercfg: SharedPreferences
    private lateinit var sceneBlackList: SharedPreferences
    private lateinit var sceneConfigInfo: SceneConfigInfo
    private lateinit var app: String
    private var cgroupValues: Array<String> = emptyArray()
    private var cgroupNames: Array<String> = emptyArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra("app")
        if (packageName.isNullOrEmpty()) {
            finish()
            return
        }
        app = packageName

        binding = ActivityAppTuningBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setBackArrow()

        store = SceneConfigStore(context)
        spfPowercfg = getSharedPreferences(SpfConfig.POWER_CONFIG_SPF, MODE_PRIVATE)
        sceneBlackList = getSharedPreferences(SpfConfig.SCENE_BLACK_LIST, MODE_PRIVATE)
        sceneConfigInfo = store.getAppConfig(app)
        cgroupValues = resources.getStringArray(R.array.cgroup_mem_values)
        cgroupNames = resources.getStringArray(R.array.cgroup_mem_options)

        if (!loadAppHeader()) {
            return
        }
        bindOptions()
    }

    override fun onResume() {
        super.onResume()
        title = getString(R.string.app_tuning_title)
    }

    override fun finish() {
        if (::app.isInitialized) {
            setResult(RESULT_OK, Intent().putExtra("app", app))
        }
        super.finish()
    }

    private fun loadAppHeader(): Boolean {
        try {
            val packageInfo = packageManager.getPackageInfo(app, 0)
            val applicationInfo = packageInfo.applicationInfo
            binding.appTuningName.text = applicationInfo?.loadLabel(packageManager) ?: packageInfo.packageName
            binding.appTuningPackage.text = packageInfo.packageName
            val icon = applicationInfo?.loadIcon(packageManager) ?: packageManager.defaultActivityIcon
            binding.appTuningIcon.setImageDrawable(icon)
            return true
        } catch (ex: Exception) {
            Toast.makeText(applicationContext, "Selected app has been uninstalled!", Toast.LENGTH_SHORT).show()
            finish()
            return false
        }
    }

    private fun bindOptions() {
        // Scene mode allowed (stored inverted: blacklist entry = disabled)
        binding.appTuningSceneMode.isChecked = !sceneBlackList.contains(app)
        binding.appTuningSceneMode.setOnClickListener {
            if ((it as Switch).isChecked) {
                sceneBlackList.edit().remove(app).apply()
            } else {
                sceneBlackList.edit().putBoolean(app, true).apply()
            }
            notifyService(app)
        }

        // Performance mode
        binding.appTuningPerfValue.text = ModeSwitcher.getModName(spfPowercfg.getString(app, "") ?: "")
        binding.appTuningPerf.setOnClickListener {
            DialogAppPowerConfig(this, spfPowercfg.getString(app, ""), object : DialogAppPowerConfig.IResultCallback {
                override fun onChange(mode: String?) {
                    spfPowercfg.edit().run {
                        if (mode.isNullOrEmpty()) {
                            remove(app)
                        } else {
                            putString(app, mode)
                        }
                    }.apply()
                    binding.appTuningPerfValue.text = ModeSwitcher.getModName(spfPowercfg.getString(app, "") ?: "")
                    notifyService(app, mode ?: "")
                }
            }).show()
        }

        // Independent brightness
        binding.appTuningBrightness.isChecked = sceneConfigInfo.aloneLight
        binding.appTuningBrightness.setOnClickListener {
            val switch = it as Switch
            if (switch.isChecked && !WriteSettings().checkPermission(this)) {
                WriteSettings().requestPermission(this)
                Toast.makeText(applicationContext, getString(R.string.scene_need_write_sys_settings), Toast.LENGTH_SHORT).show()
                switch.isChecked = false
                return@setOnClickListener
            }
            sceneConfigInfo.aloneLight = switch.isChecked
            saveConfig()
        }

        // GPS
        binding.appTuningGps.isChecked = sceneConfigInfo.gpsOn
        binding.appTuningGps.setOnClickListener {
            sceneConfigInfo.gpsOn = (it as Switch).isChecked
            saveConfig()
        }

        // Screen orientation
        binding.appTuningRotationValue.text = DialogAppOrientation.Transform.getName(sceneConfigInfo.screenOrientation)
        binding.appTuningRotation.setOnClickListener {
            DialogAppOrientation(this, sceneConfigInfo.screenOrientation, object : DialogAppOrientation.IResultCallback {
                override fun onChange(value: Int, name: String?) {
                    sceneConfigInfo.screenOrientation = value
                    binding.appTuningRotationValue.text = DialogAppOrientation.Transform.getName(value)
                    saveConfig()
                }
            }).show()
        }

        // Performance monitor
        binding.appTuningMonitor.isChecked = sceneConfigInfo.showMonitor
        binding.appTuningMonitor.setOnClickListener {
            sceneConfigInfo.showMonitor = (it as Switch).isChecked
            saveConfig()
        }

        // Memory cgroup (foreground / background)
        binding.appTuningCgroupFgValue.text = cgroupName(sceneConfigInfo.fgCGroupMem)
        binding.appTuningCgroupFg.setOnClickListener {
            sceneConfigInfo.fgCGroupMem = nextCGroup(sceneConfigInfo.fgCGroupMem)
            binding.appTuningCgroupFgValue.text = cgroupName(sceneConfigInfo.fgCGroupMem)
            saveConfig()
        }
        binding.appTuningCgroupBgValue.text = cgroupName(sceneConfigInfo.bgCGroupMem)
        binding.appTuningCgroupBg.setOnClickListener {
            sceneConfigInfo.bgCGroupMem = nextCGroup(sceneConfigInfo.bgCGroupMem)
            binding.appTuningCgroupBgValue.text = cgroupName(sceneConfigInfo.bgCGroupMem)
            saveConfig()
        }
    }

    private fun cgroupName(value: String?): String {
        val index = cgroupValues.indexOf(value ?: "")
        return if (index >= 0) cgroupNames[index] else "Unknown"
    }

    private fun nextCGroup(current: String?): String {
        var index = cgroupValues.indexOf(current ?: "")
        if (index < 0) {
            index = 0
        }
        return cgroupValues[(index + 1) % cgroupValues.size]
    }

    private fun saveConfig() {
        if (!store.setAppConfig(sceneConfigInfo)) {
            Toast.makeText(applicationContext, getString(R.string.config_save_fail), Toast.LENGTH_LONG).show()
        } else {
            notifyService(app)
        }
    }

    private fun notifyService(app: String, mode: String? = null) {
        if (AccessibleServiceHelper().serviceRunning(this)) {
            EventBus.publish(EventType.SCENE_APP_CONFIG, HashMap<String, Any>().apply {
                put("app", app)
                if (mode != null) {
                    put("mode", mode)
                }
            })
        }
    }
}
