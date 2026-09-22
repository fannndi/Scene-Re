package com.omarea.vtools.privilege

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.omarea.Scene
import com.omarea.common.shell.KeepShellAsync
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.ShellMode
import com.omarea.common.shell.ShellModeProvider
import com.omarea.common.shell.ShizukuShellProvider
import com.omarea.permissions.CheckRootStatus
import com.omarea.store.SpfConfig
import com.omarea.vtools.BuildConfig
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import rikka.sui.Sui
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Privilege tiers supported by Scene:
 *
 * - [PrivilegeTier.ROOT]: all commands run through `su` (full access).
 * - [PrivilegeTier.SHIZUKU]: commands run in a shell hosted by a Shizuku user service (shell uid,
 *   no direct write access to most sysfs nodes).
 * - [PrivilegeTier.NON_ROOT]: commands run as the app itself; only readable sysfs nodes work.
 *
 * The selected tier is persisted and routed into the common shell layer through
 * [ShellModeProvider], so every existing `KeepShell` call automatically uses the right backend.
 */
@OptIn(DelicateCoroutinesApi::class)
object PrivilegeManager : ShizukuShellProvider {
    private const val TAG = "ScenePrivilege"
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val SHELL_SERVICE_TIMEOUT_SECONDS = 8L
    const val SHIZUKU_PERMISSION_REQUEST_CODE = 4201

    @Volatile
    var tier: PrivilegeTier = PrivilegeTier.ROOT
        private set

    @Volatile
    var rootAvailable: Boolean = false
        private set

    @Volatile
    var shizukuAvailable: Boolean = false
        private set

    @Volatile
    var shizukuPermissionGranted: Boolean = false
        private set

    @Volatile
    private var shellService: IShizukuShellService? = null
    private var shellServiceBound = false
    @Volatile
    private var shellLatch = CountDownLatch(1)

    private val handler = Handler(Looper.getMainLooper())

    /** Tier actually used for shell routing after availability fallback. */
    val effectiveTier: PrivilegeTier
        get() {
            return when (tier) {
                PrivilegeTier.ROOT -> if (rootAvailable) PrivilegeTier.ROOT else if (shizukuReady) PrivilegeTier.SHIZUKU else PrivilegeTier.NON_ROOT
                PrivilegeTier.SHIZUKU -> if (shizukuReady) PrivilegeTier.SHIZUKU else if (rootAvailable) PrivilegeTier.ROOT else PrivilegeTier.NON_ROOT
                PrivilegeTier.NON_ROOT -> PrivilegeTier.NON_ROOT
            }
        }

    val shizukuReady: Boolean
        get() = shizukuAvailable && shizukuPermissionGranted

    /** True when the app can run commands outside its own uid (root or Shizuku). */
    val isPrivileged: Boolean
        get() = effectiveTier != PrivilegeTier.NON_ROOT

    /** True when commands run as uid 0. */
    val hasRootAccess: Boolean
        get() = effectiveTier == PrivilegeTier.ROOT

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received")
        refreshShizuku()
        if (effectiveTier == PrivilegeTier.SHIZUKU) {
            bindShellService()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.i(TAG, "Shizuku binder dead")
        shizukuAvailable = false
        shizukuPermissionGranted = false
        shellService = null
        shellServiceBound = false
        applyMode()
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
            shizukuPermissionGranted = grantResult == PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "Shizuku permission result: $shizukuPermissionGranted")
            if (shizukuPermissionGranted) {
                bindShellService()
            }
            applyMode()
        }
    }

    private val shellServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            shellService = if (service != null) IShizukuShellService.Stub.asInterface(service) else null
            shellLatch.countDown()
            Log.i(TAG, "Shizuku shell service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
            Log.i(TAG, "Shizuku shell service disconnected")
        }
    }

    /** Called once from [Scene]; never blocks the caller. */
    fun init(context: Context) {
        try {
            // Sui support: allows root users who installed Sui instead of Shizuku to use the same API.
            Sui.init(context.packageName)
        } catch (ex: Throwable) {
            Log.d(TAG, "Sui not available: " + ex.message)
        }

        tier = PrivilegeTier.fromStorage(Scene.globalConfig.getString(SpfConfig.GLOBAL_SPF_PRIVILEGE_TIER, null)) ?: PrivilegeTier.ROOT
        // Last known root state, refreshed asynchronously below.
        rootAvailable = Scene.getBoolean("root", false)

        ShellModeProvider.shizukuShellProvider = this
        applyMode()

        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        } catch (ex: Throwable) {
            Log.d(TAG, "Shizuku listeners not registered: " + ex.message)
        }

        refreshShizuku()
        if (effectiveTier == PrivilegeTier.SHIZUKU) {
            bindShellService()
        }

        GlobalScope.launch(Dispatchers.IO) {
            detectRoot()
        }
    }

    fun setTier(context: Context, newTier: PrivilegeTier) {
        tier = newTier
        Scene.globalConfig.edit().putString(SpfConfig.GLOBAL_SPF_PRIVILEGE_TIER, newTier.storageValue).apply()
        applyMode()
        resetShells()
        if (effectiveTier == PrivilegeTier.SHIZUKU) {
            bindShellService()
        } else {
            unbindShellService()
        }
        Log.i(TAG, "Privilege tier changed to ${newTier.storageValue} (effective ${effectiveTier.storageValue})")
    }

    fun refreshShizuku() {
        try {
            shizukuAvailable = Shizuku.pingBinder()
            shizukuPermissionGranted = shizukuAvailable && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (ex: Throwable) {
            shizukuAvailable = false
            shizukuPermissionGranted = false
        }
        Log.i(TAG, "Shizuku available=$shizukuAvailable granted=$shizukuPermissionGranted")
    }

    fun requestShizukuPermission() {
        try {
            if (Shizuku.isPreV11()) {
                Log.w(TAG, "Shizuku is too old to request permission from the app")
                return
            }
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        } catch (ex: Throwable) {
            Log.e(TAG, "Failed to request Shizuku permission: " + ex.message)
        }
    }

    /** Probes `su` without touching the persistent shell; safe to call on any background thread. */
    fun detectRoot(): Boolean {
        val detected = probeRoot()
        rootAvailable = detected
        CheckRootStatus.applyRootStatus(detected)
        applyMode()
        Log.i(TAG, "Root available=$detected")
        return detected
    }

    fun refreshAll() {
        refreshShizuku()
        GlobalScope.launch(Dispatchers.IO) {
            detectRoot()
        }
    }

    fun isShizukuInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (ex: Exception) {
            false
        }
    }

    fun openShizukuApp(context: Context): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            if (intent == null) {
                false
            } else {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            }
        } catch (ex: Exception) {
            false
        }
    }

    fun bindShellService() {
        if (shellServiceBound || !shizukuReady) {
            return
        }
        try {
            if (Shizuku.getVersion() < 10) {
                Log.w(TAG, "Shizuku API 10 is required for user services")
                return
            }
            shellLatch = CountDownLatch(1)
            val args = Shizuku.UserServiceArgs(
                ComponentName(BuildConfig.APPLICATION_ID, ShizukuShellService::class.java.name)
            )
                .daemon(false)
                .processNameSuffix("shell")
                .debuggable(BuildConfig.DEBUG)
                .version(BuildConfig.VERSION_CODE)
            Shizuku.bindUserService(args, shellServiceConnection)
            shellServiceBound = true
        } catch (ex: Throwable) {
            Log.e(TAG, "Failed to bind Shizuku shell service: " + ex.message)
        }
    }

    private fun unbindShellService() {
        if (!shellServiceBound) {
            return
        }
        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(BuildConfig.APPLICATION_ID, ShizukuShellService::class.java.name)
            )
                .daemon(false)
                .processNameSuffix("shell")
                .debuggable(BuildConfig.DEBUG)
                .version(BuildConfig.VERSION_CODE)
            Shizuku.unbindUserService(args, shellServiceConnection, true)
        } catch (ex: Throwable) {
            Log.d(TAG, "Failed to unbind Shizuku shell service: " + ex.message)
        }
        shellService = null
        shellServiceBound = false
    }

    /** Called by the common shell layer; may block briefly while the service connects. */
    override fun createShell(): Process? {
        var service = shellService
        if (service == null) {
            bindShellService()
            try {
                shellLatch.await(SHELL_SERVICE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (ex: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            service = shellService
        }
        if (service == null) {
            Log.e(TAG, "Shizuku shell service is not connected")
            return null
        }
        return try {
            val descriptors = service.openShell(arrayOf("sh"))
            if (descriptors == null || descriptors.size < 3) {
                Log.e(TAG, "Shizuku shell service returned invalid descriptors")
                null
            } else {
                ShizukuShellProcess(service, descriptors[0], descriptors[1], descriptors[2])
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to open Shizuku shell: " + ex.message)
            null
        }
    }

    private fun applyMode() {
        ShellModeProvider.mode = when (effectiveTier) {
            PrivilegeTier.ROOT -> ShellMode.ROOT
            PrivilegeTier.SHIZUKU -> ShellMode.SHIZUKU
            PrivilegeTier.NON_ROOT -> ShellMode.NON_ROOT
        }
    }

    private fun resetShells() {
        try {
            KeepShellPublic.destroyAll()
            KeepShellAsync.destoryAll()
        } catch (ex: Exception) {
            Log.d(TAG, "Failed to reset shells: " + ex.message)
        }
    }

    private fun probeRoot(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            process.outputStream.write("id -u\nexit\nexit\n".toByteArray())
            process.outputStream.flush()
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(10, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
            }
            val uid = output.trim().lines().lastOrNull { it.isNotBlank() }?.trim() ?: ""
            uid == "0"
        } catch (ex: Exception) {
            Log.d(TAG, "Root probe failed: " + ex.message)
            false
        }
    }
}

/** Privilege tier persisted in [SpfConfig.GLOBAL_SPF_PRIVILEGE_TIER]. */
enum class PrivilegeTier(val storageValue: String) {
    ROOT("root"),
    SHIZUKU("shizuku"),
    NON_ROOT("non_root");

    companion object {
        fun fromStorage(value: String?): PrivilegeTier? {
            return values().firstOrNull { it.storageValue == value }
        }
    }
}
