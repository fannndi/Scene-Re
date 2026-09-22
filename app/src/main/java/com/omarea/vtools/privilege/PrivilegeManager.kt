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
import com.omarea.library.shell.FrameworkStats
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

    /** How long to wait for the Shizuku binder to arrive before probing capabilities. */
    private const val SHIZUKU_BINDER_WAIT_STEPS = 40
    private const val SHIZUKU_BINDER_WAIT_STEP_MILLIS = 250L

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

    /** True when Shizuku reports "deny and don't ask again"; the user must grant it in the Shizuku app. */
    @Volatile
    var shizukuPermissionPermanentlyDenied: Boolean = false
        private set

    /** Shizuku service version, or -1 when the binder is not available. */
    @Volatile
    var shizukuVersion: Int = -1
        private set

    /** True when the Shizuku backend runs as uid 0 (Shizuku started with root or Sui). */
    @Volatile
    var shizukuIsRoot: Boolean = false
        private set

    /** True when Sui is the active backend instead of the Shizuku app. */
    @Volatile
    var suiActive: Boolean = false
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

    /** True when commands run as uid 0, either through `su` or a root-backed Shizuku. */
    val hasRootAccess: Boolean
        get() = effectiveTier == PrivilegeTier.ROOT || (effectiveTier == PrivilegeTier.SHIZUKU && shizukuIsRoot)

    /** Single source of truth for the user service identity; must be identical for bind and unbind. */
    private val shellServiceArgs: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, ShizukuShellService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("shell")
            // Explicit tag: Shizuku uses it to identify the service, class names are unstable after R8.
            .tag("scene-shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received")
        refreshShizuku()
        if (effectiveTier == PrivilegeTier.SHIZUKU) {
            bindShellService()
        }
        // do not probe here: the user service has not connected yet, so the probe would measure
        // the app's own shell. The connection callback re-probes once the backend is real.
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.i(TAG, "Shizuku binder dead")
        shizukuAvailable = false
        shizukuPermissionGranted = false
        shizukuPermissionPermanentlyDenied = false
        shizukuVersion = -1
        shizukuIsRoot = false
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
            // The tier only becomes usable here, so this is the first moment a capability probe can
            // produce a truthful answer.
            reprobeCapabilities()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
            Log.i(TAG, "Shizuku shell service disconnected")
        }
    }

    /**
     * Re-probes capabilities off the main thread, clearing any cached verdicts first.
     *
     * Every shell-backed source remembers a failure so it can stop retrying, so a tier change must
     * reset that memory *and* the cached snapshot, otherwise a source that failed on the old tier
     * would stay disabled on the new one.
     */
    private fun reprobeCapabilities() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                FrameworkStats.resetWarnings()
                ShellCapabilityProbeImpl.probe()
            } catch (ex: Throwable) {
                Log.d(TAG, "Capability re-probe skipped: " + ex.message)
            }
        }
    }

    /** Called once from [Scene]; never blocks the caller. */
    fun init(context: Context) {
        try {
            // Sui support: allows root users who installed Sui instead of Shizuku to use the same API.
            // ShizukuProvider also initializes Sui automatically since Shizuku v12.1.0; calling it
            // again is harmless and also covers builds where the provider is not registered.
            suiActive = Sui.init(context.packageName)
        } catch (ex: Throwable) {
            suiActive = false
            Log.d(TAG, "Sui not available: " + ex.message)
        }

        tier = PrivilegeTier.fromStorage(Scene.globalConfig.getString(SpfConfig.GLOBAL_SPF_PRIVILEGE_TIER, null)) ?: PrivilegeTier.ROOT
        // Last known root state, refreshed asynchronously below.
        rootAvailable = Scene.getBoolean("root", false)

        ShellModeProvider.shizukuShellProvider = this
        ShellCapabilityProbeImpl.register()
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
            // The Shizuku binder and its user service arrive asynchronously, well after init()
            // returns. Probing before they are up would measure the app's own `sh`, cache a
            // NON_ROOT snapshot, and permanently hide every feature Shizuku does support - the
            // monitor then retries denied reads forever. Wait for the tier to settle first.
            awaitTierSettled()
            ShellCapabilityProbeImpl.probe()
        }
    }

    /**
     * Blocks until the shell backend for the selected tier is usable, or the wait expires.
     *
     * The selected [tier] is read from storage synchronously, so it is known immediately; what
     * arrives late is the Shizuku binder and its user service. When the user selected Shizuku this
     * therefore waits for [shizukuReady] and then for the shell service to connect.
     *
     * The wait is driven by the *configured* tier rather than [effectiveTier], because
     * `effectiveTier` degrades to NON_ROOT while Shizuku is still starting up - waiting on it
     * would return immediately and probe the wrong backend, which is the bug this guards against.
     */
    private fun awaitTierSettled() {
        if (tier != PrivilegeTier.SHIZUKU) {
            // Root and explicit non-root need no wait: their backend is available immediately.
            return
        }
        var waited = 0L
        val binderLimit = SHIZUKU_BINDER_WAIT_STEPS * SHIZUKU_BINDER_WAIT_STEP_MILLIS
        while (!shizukuReady && waited < binderLimit) {
            if (tier != PrivilegeTier.SHIZUKU) {
                // The user changed tier while we were waiting.
                return
            }
            try {
                Thread.sleep(SHIZUKU_BINDER_WAIT_STEP_MILLIS)
            } catch (ex: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            waited += SHIZUKU_BINDER_WAIT_STEP_MILLIS
        }
        if (!shizukuReady) {
            Log.w(TAG, "Shizuku did not become ready within ${binderLimit}ms; probing current backend")
            return
        }
        bindShellService()
        if (!shellServiceBound) {
            return
        }
        try {
            if (!shellLatch.await(SHELL_SERVICE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w(TAG, "Shizuku shell service did not connect within ${SHELL_SERVICE_TIMEOUT_SECONDS}s")
            }
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
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
        // The capability set changes with the tier, and every source caches its own failures, so
        // wait for the new backend to come up and then re-measure from a clean slate.
        GlobalScope.launch(Dispatchers.IO) {
            awaitTierSettled()
            FrameworkStats.resetWarnings()
            ShellCapabilityProbeImpl.probe()
        }
    }

    /**
     * Re-reads the Shizuku state and re-applies the shell routing.
     *
     * `applyMode()` is called here because the Shizuku flags decide [effectiveTier], and
     * [ShellModeProvider.mode] must follow. Without it the flags can report "Shizuku is ready"
     * while every shell command still runs through the previous backend - the app looks connected
     * but silently has app-uid access only, which is exactly the state that makes a capability
     * probe report capabilities the tier does not have.
     */
    fun refreshShizuku() {
        try {
            shizukuAvailable = Shizuku.pingBinder()
            if (shizukuAvailable) {
                shizukuVersion = Shizuku.getVersion()
                shizukuIsRoot = Shizuku.getUid() == 0
                shizukuPermissionGranted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                shizukuPermissionPermanentlyDenied = !shizukuPermissionGranted && !Shizuku.isPreV11() &&
                        Shizuku.shouldShowRequestPermissionRationale()
            } else {
                shizukuVersion = -1
                shizukuIsRoot = false
                shizukuPermissionGranted = false
                shizukuPermissionPermanentlyDenied = false
            }
        } catch (ex: Throwable) {
            shizukuAvailable = false
            shizukuVersion = -1
            shizukuIsRoot = false
            shizukuPermissionGranted = false
            shizukuPermissionPermanentlyDenied = false
        }
        applyMode()
        Log.i(TAG, "Shizuku available=$shizukuAvailable version=$shizukuVersion uid0=$shizukuIsRoot granted=$shizukuPermissionGranted denied=$shizukuPermissionPermanentlyDenied mode=${ShellModeProvider.mode}")
    }

    /**
     * Requests the Shizuku API permission following the official flow:
     * pre-v11 is unsupported, "deny and don't ask again" must be resolved in the Shizuku app.
     */
    fun requestShizukuPermission() {
        try {
            if (Shizuku.isPreV11()) {
                Log.w(TAG, "Shizuku pre-v11 does not support in-app permission requests")
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                shizukuPermissionGranted = true
                return
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                shizukuPermissionPermanentlyDenied = true
                Log.w(TAG, "Shizuku permission was denied permanently; grant it in the Shizuku app")
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
            awaitTierSettled()
            ShellCapabilityProbeImpl.probe()
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
            Shizuku.bindUserService(shellServiceArgs, shellServiceConnection)
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
            Shizuku.unbindUserService(shellServiceArgs, shellServiceConnection, true)
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
            // destroyAll() only covers named instances; tryExit() closes the default and secondary shells.
            KeepShellPublic.destroyAll()
            KeepShellPublic.tryExit()
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
