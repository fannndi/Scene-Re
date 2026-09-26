package com.omarea.vtools.activities

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.omarea.Scene
import com.omarea.common.ui.DialogHelper
import com.omarea.common.ui.ThemeMode
import com.omarea.library.permissions.GeneralPermissions
import com.omarea.permissions.Busybox
import com.omarea.permissions.CheckRootStatus
import com.omarea.permissions.WriteSettings
import com.omarea.store.SpfConfig
import com.omarea.utils.AccessibleServiceHelper
import com.omarea.utils.WindowCompatHelper
import com.omarea.vtools.R
import com.omarea.vtools.databinding.ActivityStartSplashBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.*

class ActivityStartSplash : Activity() {
    companion object {
        var finished = false
    }

    private lateinit var globalSPF: SharedPreferences
    private lateinit var binding: ActivityStartSplashBinding
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        globalSPF = getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)

        val themeMode = ThemeSwitch.switchTheme(this)
        super.onCreate(savedInstanceState)

        binding = ActivityStartSplashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        updateThemeStyle(themeMode)

        checkPermissions()
    }

    /**
     * Agreement accept/decline
     */
    private fun initContractAction() {
        val view = layoutInflater.inflate(R.layout.dialog_danger_agreement, null)
        val dialog = DialogHelper.customDialog(this, view, false)
        val btnConfirm = view.findViewById<Button>(R.id.btn_confirm)
        val agreement = view.findViewById<CompoundButton>(R.id.agreement)
        val timer = Timer()
        var timeout = 5
        var clickItems = 0
        timer.schedule(object : TimerTask() {
            override fun run() {
                Scene.post {
                    if (timeout > 0) {
                        timeout --
                        btnConfirm.text = timeout.toString() + "s"
                    } else {
                        timer.cancel()
                        btnConfirm.text = "Agree"
                    }
                }
            }
        }, 0, 1000)
        view.findViewById<View>(R.id.btn_cancel).setOnClickListener {
            timer.cancel()
            dialog.dismiss()
            finish()
        }
        btnConfirm.setOnClickListener {
            if (!agreement.isChecked) {
                return@setOnClickListener
            }
            if (timeout > 0 && clickItems < 10) { // 10 rapid taps skip the countdown
                clickItems++
                return@setOnClickListener
            }

            timer.cancel()
            dialog.dismiss()
            globalSPF.edit().putBoolean(SpfConfig.GLOBAL_SPF_CONTRACT, true).apply()
            checkPermissions()
        }
    }

    /**
     * Adjust the screen theme style
     */
    private fun updateThemeStyle(themeMode: ThemeMode) {
        val lightBars = !themeMode.isDarkMode
        // The splash is intentionally edge-to-edge so the full-bleed background shows
        // behind the system bars; the root view supplies the colour. On Android 15+
        // with targetSdk 36 Window.setStatusBarColor()/setNavigationBarColor() are
        // ignored, so only the icon tint below actually has an effect.
        if (themeMode.isDarkMode) {
            binding.splashRoot.setBackgroundColor(Color.argb(255, 0, 0, 0))
        } else {
            binding.splashRoot.setBackgroundColor(Color.argb(255, 255, 255, 255))
        }

        WindowCompatHelper.applyEdgeToEdge(window, lightStatusBars = lightBars, lightNavBars = lightBars)
    }

    /**
     * Start checking required permissions
     */
    private fun checkPermissions() {
        checkRoot()
    }

    private class CheckFileWrite(private val context: ActivityStartSplash) : Runnable {
        override fun run() {
            context.updateStartStateText("Check and obtain required permissions……")
            context.hasRoot = true

            context.checkFileWrite(InstallBusybox(context))
        }
    }

    private class InstallBusybox(private val context: ActivityStartSplash) : Runnable {
        override fun run() {
            context.updateStartStateText("Check if Busybox is installed...")
            Busybox(context).forceInstall {
                context.startToFinish()
            }
        }

    }

    private fun checkPermission(permission: String): Boolean = PermissionChecker.checkSelfPermission(this.applicationContext, permission) == PermissionChecker.PERMISSION_GRANTED

    /**
     * Resumes the startup chain from [onRequestPermissionsResult]. This screen
     * extends plain `android.app.Activity` (it runs before the AndroidX theme is
     * applied), so the ActivityResult API is unavailable; the callback-based
     * `ActivityCompat.requestPermissions` + `onRequestPermissionsResult` pair is
     * the correct mechanism here.
     */
    private var onStoragePermissionResult: (() -> Unit)? = null

    private val PERMISSION_REQUEST_CODE = 0x11

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val next = onStoragePermissionResult
            onStoragePermissionResult = null
            next?.invoke()
        }
    }

    /**
     * Check permissions, mainly file read/write
     */
    private fun checkFileWrite(next: Runnable) {
        val activity = this
        uiScope.launch {
            if (hasRoot) {
                GeneralPermissions(activity).grantPermissions()
                val serviceHelper = AccessibleServiceHelper()
                if (!serviceHelper.serviceRunning(activity)) {
                    serviceHelper.startSceneModeService(activity)
                }
            }

            // Request the write-settings permission
            val writeSettings = WriteSettings()
            if (!writeSettings.checkPermission(applicationContext)) {
                if (hasRoot) {
                    writeSettings.setPermissionByRoot(applicationContext)
                } else {
                    writeSettings.requestPermission(applicationContext)
                }
            }

            val storageGranted = checkPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    && checkPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            val notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                    || ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

            if (!storageGranted || !notificationsGranted) {
                // minSdk is 29, so the old `SDK_INT >= M` guard was always true and
                // its else-branch was dead. Both arrays listed the same permissions
                // apart from REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, which is not a
                // runtime permission and has no effect in a request. The storage and
                // notification asks are now combined into one request so the chain
                // resumes exactly once from onRequestPermissionsResult.
                val requested = ArrayList<String>()
                if (!storageGranted) {
                    requested.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                    requested.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    requested.add(Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS)
                    requested.add(Manifest.permission.WAKE_LOCK)
                }
                if (!notificationsGranted) {
                    requested.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                onStoragePermissionResult = { next.run() }
                ActivityCompat.requestPermissions(activity, requested.toTypedArray(), PERMISSION_REQUEST_CODE)
            } else {
                next.run()
            }
        }
    }

    private var hasRoot = false

    private fun checkRoot() {
        val disableSeLinux = globalSPF.getBoolean(SpfConfig.GLOBAL_SPF_DISABLE_ENFORCE, false)
        CheckRootStatus(this, {
            if (globalSPF.getBoolean(SpfConfig.GLOBAL_SPF_CONTRACT, false)) {
                CheckFileWrite(this).run()
            } else {
                initContractAction()
            }
        }, disableSeLinux, InstallBusybox(this)).forceGetRoot()
    }

    /**
     * Startup complete
     */
    private fun startToFinish() {
        updateStartStateText("Completed!")

        val intent = Intent(this.applicationContext, ActivityMain::class.java)
        startActivity(intent)
        finished = true
        finish()
    }

    private fun updateStartStateText(text: String) {
        binding.startStateText.text = text
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }
}
