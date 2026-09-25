package com.omarea.utils

import android.content.Context
import android.os.Process
import dalvik.system.DexClassLoader
import java.io.File
import java.lang.reflect.Method

/**
 * MIUI's own booster service, reached through the platform class
 * `com.miui.performance.MiuiBooster` shipped in `/system/framework/MiuiBooster.jar`.
 *
 * <h3>Why this exists</h3>
 *
 * Scene already raises a game's process priority with `renice` / `ionice`, but
 * that only moves the scheduler's opinion of the process. It does not touch
 * DVFS. MIUI exposes a real API for that - CPU / GPU / VPU / IO / memory /
 * network frequency requests and thread priority - and its own apps
 * (MiGameCenter, the gallery, the browser) use it. Going through the same
 * channel means the requests compose with MIUI's perf HAL instead of fighting
 * it, and the platform already knows how to translate them into node writes.
 *
 * <h3>Authorization</h3>
 *
 * The service gates every call on a UID allow-list, not on a signature
 * permission. `addin/miui_booster.sh authorize <uid>` adds Scene's UID to
 * `persist.sys.mibridge_auth_uids`; only then does [checkPermission] return true
 * and the request methods stop answering [PERMISSION_NOT_GRANTED].
 *
 * <h3>Contract</h3>
 *
 * Verified against the shipped DEX (build-tools `dexdump`):
 *
 * ```
 * public MiuiBooster()                                   // no-arg constructor
 * public boolean checkPermission(String pkg, int uid)
 * public int  requestCpuHighFreq(int uid, int level, int timeoutMs)
 * public int  requestGpuHighFreq(int uid, int level, int timeoutMs)
 * public int  requestVpuHighFreq(int uid, int level, int timeoutMs)
 * public int  requestIOHighFreq(int uid, int level, int timeoutMs)
 * public int  requestMemory(int uid, int level, int timeoutMs)
 * public int  requestNetwork(int uid, int level, int timeoutMs)
 * public int  requestThreadPriority(int uid, int tid, int level)
 * public int  cancelCpuHighFreq(int uid)      // and the same for gpu/vpu/io/mem/net
 * public int  cancelThreadPriority(int uid, int tid)
 * public static final int REQUEST_SUCCEEDED / REQUEST_FAILED / PERMISSION_NOT_GRANTED
 * ```
 *
 * `level` is the requested device tier, 1..3 (the jar validates device-level
 * strings with `c:[1-3],g:[1-3]`); 3 is the top tier.
 *
 * Everything here is best effort: a missing class, a refused permission or an
 * unexpected ABI all degrade to "unavailable" and leave the sysfs-based tuning
 * untouched. Nothing in this class is required for the rest of the app to work.
 */
object MiuiBoosterHints {
    private const val JAR_PATH = "/system/framework/MiuiBooster.jar"
    private const val CLASS_NAME = "com.miui.performance.MiuiBooster"

    /** Return codes declared by the jar. */
    const val REQUEST_SUCCEEDED = 0
    const val REQUEST_FAILED = -1
    const val PERMISSION_NOT_GRANTED = -2

    /** Device tiers accepted by the service. */
    const val LEVEL_HIGH = 3
    const val LEVEL_MIDDLE = 2
    const val LEVEL_LOW = 1

    @Volatile
    private var probed = false

    @Volatile
    private var instance: Any? = null

    @Volatile
    private var clazz: Class<*>? = null

    @Volatile
    private var permissionGranted = false

    /** Application context, captured by [probe] for the dex cache and the package name. */
    @Volatile
    private var appContext: Context? = null

    /** Human readable probe result, rendered in Diagnostics. */
    @Volatile
    var status: String = "not probed"
        private set

    /**
     * Load the class and build one instance. Safe to call repeatedly; the first
     * call wins and later ones only report the cached status.
     */
    @Synchronized
    fun probe(context: Context): String {
        if (probed) {
            return status
        }
        probed = true
        appContext = context.applicationContext
        status = try {
            val loaded = loadClass(context)
            if (loaded == null) {
                "unavailable: $CLASS_NAME not found"
            } else {
                clazz = loaded
                val ctor = loaded.getDeclaredConstructor()
                ctor.isAccessible = true
                instance = ctor.newInstance()
                "available"
            }
        } catch (ex: Throwable) {
            "unavailable: " + ex.javaClass.simpleName + ": " + ex.message
        }
        return status
    }

    /**
     * Prefer the platform loader: MIUI puts the jar on the system class path for
     * its own apps. When that fails (a non-MIUI build, or a class loader that
     * does not expose it) fall back to loading the dex directly from
     * /system/framework, which is world-readable.
     */
    private fun loadClass(context: Context): Class<*>? {
        try {
            return Class.forName(CLASS_NAME)
        } catch (ignored: Throwable) {
            // fall through to the dex loader
        }
        val jar = File(JAR_PATH)
        if (!jar.isFile) {
            return null
        }
        val cacheDir = File(context.codeCacheDir, "miuibooster").apply { mkdirs() }
        val loader = DexClassLoader(
            JAR_PATH,
            cacheDir.absolutePath,
            null,
            javaClass.classLoader?.parent
        )
        return loader.loadClass(CLASS_NAME)
    }

    fun isAvailable(context: Context): Boolean {
        if (!probed) {
            probe(context)
        }
        return instance != null && clazz != null
    }

    /**
     * Ask the service whether this UID is allowed to send requests.
     *
     * This also sets the flag the jar checks internally before every request, so
     * it must succeed once per process before any request is sent. The result is
     * cached; call [invalidate] after changing the allow-list.
     */
    @Synchronized
    fun checkPermission(context: Context, uid: Int = Process.myUid()): Boolean {
        if (!isAvailable(context)) {
            return false
        }
        if (permissionGranted) {
            return true
        }
        permissionGranted = try {
            val pkg = context.packageName
            val method: Method? = clazz?.getMethod(
                "checkPermission", String::class.java, Int::class.javaPrimitiveType
            )
            val granted = method?.invoke(instance, pkg, uid) as? Boolean ?: false
            SceneLog.i("MiuiBoosterHints", "checkPermission($pkg, $uid) -> $granted")
            granted
        } catch (ex: Throwable) {
            status = "checkPermission failed: " + ex.javaClass.simpleName
            SceneLog.e("MiuiBoosterHints", "checkPermission failed", ex)
            false
        }
        return permissionGranted
    }

    /** True once [checkPermission] has succeeded for this process. */
    fun isAuthorized(): Boolean = permissionGranted

    // +---------------------------------------------------------------+
    // | Requests                                                       |
    // +---------------------------------------------------------------+

    fun requestCpu(uid: Int, level: Int = LEVEL_HIGH, timeoutMs: Int): Int =
        request("requestCpuHighFreq", uid, level, timeoutMs)

    fun requestGpu(uid: Int, level: Int = LEVEL_HIGH, timeoutMs: Int): Int =
        request("requestGpuHighFreq", uid, level, timeoutMs)

    fun requestVpu(uid: Int, level: Int = LEVEL_HIGH, timeoutMs: Int): Int =
        request("requestVpuHighFreq", uid, level, timeoutMs)

    fun requestIo(uid: Int, level: Int = LEVEL_HIGH, timeoutMs: Int): Int =
        request("requestIOHighFreq", uid, level, timeoutMs)

    fun requestMemory(uid: Int, level: Int = LEVEL_HIGH, timeoutMs: Int): Int =
        request("requestMemory", uid, level, timeoutMs)

    fun requestNetwork(uid: Int, level: Int = LEVEL_HIGH, timeoutMs: Int): Int =
        request("requestNetwork", uid, level, timeoutMs)

    /** Thread priority is (uid, tid, level) - there is no timeout. */
    fun requestThreadPriority(uid: Int, tid: Int, level: Int = LEVEL_HIGH): Int =
        request("requestThreadPriority", uid, tid, level)

    // +---------------------------------------------------------------+
    // | Cancels                                                        |
    // +---------------------------------------------------------------+

    fun cancelCpu(uid: Int): Int = cancel1("cancelCpuHighFreq", uid)
    fun cancelGpu(uid: Int): Int = cancel1("cancelGpuHighFreq", uid)
    fun cancelVpu(uid: Int): Int = cancel1("cancelVpuHighFreq", uid)
    fun cancelIo(uid: Int): Int = cancel1("cancelIOHighFreq", uid)
    fun cancelMemory(uid: Int): Int = cancel1("cancelMemory", uid)
    fun cancelNetwork(uid: Int): Int = cancel1("cancelNetwork", uid)

    /** Thread priority cancel is (uid, tid). */
    fun cancelThreadPriority(uid: Int, tid: Int): Int = try {
        val method: Method? = clazz?.getMethod(
            "cancelThreadPriority", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
        )
        method?.invoke(instance, uid, tid) as? Int ?: REQUEST_FAILED
    } catch (ex: Throwable) {
        SceneLog.e("MiuiBoosterHints", "cancelThreadPriority failed", ex)
        REQUEST_FAILED
    }

    /**
     * Release everything this UID may have acquired. Called when a game session
     * ends and on the options-layer reset, so no request outlives its game.
     *
     * Returns the number of cancels the service acknowledged. A short count is
     * not fatal - MIUI expires the timed requests on its own - but a zero count
     * means the channel is not usable and should be reported.
     */
    fun cancelAll(uid: Int): Int {
        if (!isAvailable(appContext ?: return 0)) {
            return 0
        }
        var ok = 0
        for (result in intArrayOf(
            cancelCpu(uid), cancelGpu(uid), cancelVpu(uid),
            cancelIo(uid), cancelMemory(uid), cancelNetwork(uid)
        )) {
            if (result >= 0) {
                ok++
            }
        }
        SceneLog.i("MiuiBoosterHints", "cancelAll($uid) -> $ok/6 acknowledged")
        return ok
    }

    // +---------------------------------------------------------------+
    // | Reflection plumbing                                            |
    // +---------------------------------------------------------------+

    private fun request(name: String, a: Int, b: Int, c: Int): Int {
        if (!permissionGranted) {
            SceneLog.w("MiuiBoosterHints", "$name skipped: not authorized")
            return PERMISSION_NOT_GRANTED
        }
        return try {
            val method: Method? = clazz?.getMethod(
                name,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            val result = method?.invoke(instance, a, b, c) as? Int ?: REQUEST_FAILED
            SceneLog.i("MiuiBoosterHints", "$name($a, $b, $c) -> $result")
            result
        } catch (ex: Throwable) {
            status = "$name failed: " + ex.javaClass.simpleName
            SceneLog.e("MiuiBoosterHints", "$name failed", ex)
            REQUEST_FAILED
        }
    }

    private fun cancel1(name: String, uid: Int): Int {
        return try {
            val method: Method? = clazz?.getMethod(name, Int::class.javaPrimitiveType)
            method?.invoke(instance, uid) as? Int ?: REQUEST_FAILED
        } catch (ex: Throwable) {
            SceneLog.e("MiuiBoosterHints", "$name failed", ex)
            REQUEST_FAILED
        }
    }

    /** Drop the cached authorization so the next call re-checks it. */
    @Synchronized
    fun invalidate() {
        permissionGranted = false
    }
}
