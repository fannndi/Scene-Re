package com.omarea.utils

import java.lang.reflect.Method

/**
 * QTI perf-HAL hints through the framework's hidden `android.util.BoostFramework`
 * class — the same channel MIUI's own apps use (PerformanceMode.apk, the
 * framework's game booster). The vendor config it drives is the device's
 * `vendor/etc/perf/perfboostsconfig.xml`, so on surya the game-boost hint
 * applies the sdmmagpie Type-4 `config_gameBoost` values for 15 s.
 *
 * Everything here is best effort and guarded:
 *  - the class is loaded reflectively and a missing/blocked class simply
 *    reports unavailable;
 *  - when hidden-API enforcement blocks the class, the process is exempted
 *    once through the standard VMRuntime.setHiddenApiExemptions root trick
 *    (affects only this process) and the load is retried;
 *  - the feature itself is opt-in, so a failure never changes the normal
 *    sysfs-based tuning.
 */
object QtiPerfHints {
    /** VENDOR_HINT_APP_LAUNCH in the vendor perf config (Id 0x00001081). */
    private const val VENDOR_HINT_APP_LAUNCH = 0x00001081

    /** Type 4 = config_gameBoost (SCHEDBOOST + group migrate + LPM bias). */
    private const val TYPE_GAME_BOOST = 4

    @Volatile
    private var probed = false

    @Volatile
    private var instance: Any? = null

    @Volatile
    private var hintMethod: Method? = null

    @Volatile
    private var exempted = false

    /** Human readable probe result, also rendered in Diagnostics. */
    @Volatile
    var status: String = "not probed"
        private set

    /**
     * Load BoostFramework without touching the hidden-API exemption. Used by
     * Diagnostics so the report can show the channel state read-only.
     */
    @Synchronized
    fun probe(): String {
        if (probed) {
            return status
        }
        load()
        return status
    }

    /**
     * Ensure the hint channel is usable, applying the hidden-API exemption
     * when the platform blocks the class. Returns true when a hint can be
     * sent.
     */
    @Synchronized
    fun isAvailable(): Boolean {
        if (probed && hintMethod != null) {
            return true
        }
        if (!probed) {
            load()
        }
        if (hintMethod != null) {
            return true
        }
        if (!exempted && applyHiddenApiExemptions()) {
            exempted = true
            probed = false
            load()
            return hintMethod != null
        }
        return hintMethod != null
    }

    private fun load() {
        probed = true
        try {
            val clazz = Class.forName("android.util.BoostFramework")
            val constructor = clazz.getDeclaredConstructor()
            constructor.isAccessible = true
            instance = constructor.newInstance()
            hintMethod = clazz.methods.firstOrNull { method ->
                method.name == "perfHint" &&
                    method.parameterTypes.size == 4 &&
                    method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    method.parameterTypes[1] == String::class.java &&
                    method.parameterTypes[2] == Int::class.javaPrimitiveType &&
                    method.parameterTypes[3] == Int::class.javaPrimitiveType
            }
            status = if (hintMethod != null) {
                "available (perfHint 4-arg)"
            } else {
                "BoostFramework found but perfHint(int,String,int,int) is missing"
            }
        } catch (ex: Throwable) {
            status = "unavailable: " + ex.javaClass.simpleName + ": " + ex.message
        }
    }

    /**
     * Ask the perf HAL for the vendor game boost (Type 4, 15 s) for the game.
     * Returns true when the hint was accepted (positive handle).
     */
    fun gameBoost(packageName: String): Boolean {
        if (packageName.isEmpty() || !isAvailable()) {
            return false
        }
        return try {
            val result = hintMethod?.invoke(
                instance, VENDOR_HINT_APP_LAUNCH, packageName, TYPE_GAME_BOOST, 0
            ) as? Int ?: -1
            SceneLog.i("QtiPerfHints", "game boost hint for $packageName -> $result")
            result >= 0
        } catch (ex: Throwable) {
            status = "call failed: " + ex.javaClass.simpleName + ": " + ex.message
            SceneLog.e("QtiPerfHints", "game boost hint failed", ex)
            false
        }
    }

    /**
     * Standard root technique: exempt this process from hidden-API checks so
     * the framework class becomes reachable. Only the current process is
     * affected.
     */
    private fun applyHiddenApiExemptions(): Boolean {
        return try {
            val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
            val getDeclaredMethod = Class::class.java.getDeclaredMethod(
                "getDeclaredMethod", String::class.java, arrayOf<Class<*>>().javaClass
            )
            val vmRuntimeClass = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
            val getRuntime = getDeclaredMethod.invoke(vmRuntimeClass, "getRuntime", null) as Method
            val setHiddenApiExemptions = getDeclaredMethod.invoke(
                vmRuntimeClass, "setHiddenApiExemptions", arrayOf(arrayOf<String>().javaClass)
            ) as Method
            val vmRuntime = getRuntime.invoke(null)
            setHiddenApiExemptions.invoke(vmRuntime, arrayOf("L") as Any)
            SceneLog.i("QtiPerfHints", "hidden API exemptions applied for this process")
            true
        } catch (ex: Throwable) {
            status = "hidden API exemption failed: " + ex.javaClass.simpleName
            false
        }
    }
}
