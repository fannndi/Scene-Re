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
import com.omarea.permissions.BatteryOptimization
import com.omarea.permissions.Busybox
import com.omarea.permissions.CheckRootStatus
import com.omarea.vtools.device.DeviceSupport
import com.omarea.vtools.privilege.PrivilegeManager
import com.omarea.vtools.privilege.PrivilegeTier
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
import kotlinx.coroutines.withContext
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
     * Agreement accepted or not
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
            if (timeout > 0 && clickItems < 10) { // Allow skipping the countdown after 10 taps
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
     * UI theme style adjustment
     */
    private fun updateThemeStyle(themeMode: ThemeMode) {
        val lightBars = !themeMode.isDarkMode
        if (themeMode.isDarkMode) {
            binding.splashRoot.setBackgroundColor(Color.argb(255, 0, 0, 0))
            WindowCompatHelper.setSystemBarColors(window, null, Color.argb(255, 0, 0, 0))
        } else {
            // getWindow().setNavigationBarColor(getColorAccent())
            binding.splashRoot.setBackgroundColor(Color.argb(255, 255, 255, 255))
            WindowCompatHelper.setSystemBarColors(window, null, Color.argb(255, 255, 255, 255))
        }

        WindowCompatHelper.applyEdgeToEdge(window, lightStatusBars = lightBars, lightNavBars = lightBars)
        WindowCompatHelper.setSystemBarColors(window, Color.TRANSPARENT, null)
    }

    private fun getColorAccent(): Int {
        val typedValue = TypedValue()
        this.theme.resolveAttribute(R.attr.colorAccent, typedValue, true)
        return typedValue.data
    }

    /**
     * Start checking required permissions
     */
    private fun checkPermissions() {
        val unsupported = DeviceSupport.unsupportedReason(this)
        if (unsupported != null) {
            showUnsupportedDevice(unsupported)
            return
        }
        checkRoot()
    }

    /** Scene-Re targets the POCO X3 NFC (surya) on Android 10-12 only. */
    private fun showUnsupportedDevice(reason: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.device_unsupported_title)
            .setMessage(reason)
            .setCancelable(false)
            .setPositiveButton(R.string.device_unsupported_exit) { _, _ ->
                finish()
            }
            .show()
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
     * Check permissions, mainly file read/write permission
     */
    private fun checkFileWrite(next: Runnable) {
        val activity = this
        uiScope.launch {
            // Refresh the privilege state and wait for the selected backend to come up before
            // making any decision from it. Shizuku binds asynchronously, so reading isPrivileged
            // this early used to return false and skip the accessibility service for the whole
            // launch even though Shizuku was about to become available.
            //
            // This must not run on the main dispatcher: awaitTierSettled() polls the Shizuku binder
            // with Thread.sleep for up to 10s, and blocking the main thread there froze the splash
            // into an "isn't responding" dialog. Everything that talks to the shell goes to IO; only
            // the steps that show a system dialog stay on Main.
            val privileged = withContext(Dispatchers.IO) {
                PrivilegeManager.awaitTierSettled()
                PrivilegeManager.isPrivileged
            }
            hasRoot = privileged

            if (hasRoot) {
                withContext(Dispatchers.IO) {
                    GeneralPermissions(activity).grantPermissions()
                    val serviceHelper = AccessibleServiceHelper()
                    if (!serviceHelper.serviceRunning(activity)) {
                        serviceHelper.startSceneModeService(activity)
                    }
                }
            }

            if (!(checkPermission(Manifest.permission.READ_EXTERNAL_STORAGE) && checkPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE))) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // REQUEST_IGNORE_BATTERY_OPTIMIZATIONS deliberately absent: it is not a runtime
                    // permission, so listing it here did nothing. It is handled below.
                    ActivityCompat.requestPermissions(
                            activity,
                            arrayOf(
                                    Manifest.permission.READ_EXTERNAL_STORAGE,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                    Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS,
                                    Manifest.permission.WAKE_LOCK
                            ),
                            0x11
                    )
                } else {
                    ActivityCompat.requestPermissions(
                            activity,
                            arrayOf(
                                    Manifest.permission.READ_EXTERNAL_STORAGE,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                    Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS,
                                    Manifest.permission.WAKE_LOCK
                            ),
                            0x11
                    )
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(
                            activity,
                            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                            0x12
                    )
                }
            }

            // Ask to run unrestricted in the background. Without this MIUI kills the process and
            // takes the accessibility service down with it, which is the "accessibility keeps
            // turning itself off" report. Shell first, system dialog as the fallback.
            val batteryOptimization = BatteryOptimization()
            if (!batteryOptimization.isExempt(applicationContext)) {
                val exemptedByShell = if (hasRoot) {
                    withContext(Dispatchers.IO) { batteryOptimization.grantByShell(applicationContext) }
                } else {
                    false
                }
                if (!exemptedByShell) {
                    batteryOptimization.requestExemption(applicationContext)
                }
            }

            // Request the write settings permission. With root or Shizuku the shell can allow the
            // AppOp directly, which avoids sending the user into system settings for nothing.
            val writeSettings = WriteSettings()
            if (!writeSettings.checkPermission(applicationContext)) {
                val grantedByShell = if (hasRoot) {
                    withContext(Dispatchers.IO) { writeSettings.setPermissionByRoot(applicationContext) }
                } else {
                    false
                }
                if (!grantedByShell) {
                    writeSettings.requestPermission(applicationContext)
                }
            }
            next.run()
        }
    }

    private var hasRoot = false

    private fun checkRoot() {
        if (PrivilegeManager.tier != PrivilegeTier.ROOT) {
            // Shizuku or non-root mode is selected: do not ask for su, continue with the chosen tier.
            hasRoot = PrivilegeManager.isPrivileged
            if (globalSPF.getBoolean(SpfConfig.GLOBAL_SPF_CONTRACT, false)) {
                CheckFileWrite(this).run()
            } else {
                initContractAction()
            }
            return
        }
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
     * Launch finished
     */
    private fun startToFinish() {
        updateStartStateText("Completed!")

        // First launch (or setup was never finished): walk through privilege mode, Shizuku health
        // and the permission checklist before the main UI.
        val setupCompleted = Scene.getBoolean(SpfConfig.GLOBAL_SPF_SETUP_COMPLETED, false)
        val target = if (setupCompleted) ActivityMain::class.java else com.omarea.vtools.setup.ActivitySetup::class.java
        val intent = Intent(this.applicationContext, target)
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
