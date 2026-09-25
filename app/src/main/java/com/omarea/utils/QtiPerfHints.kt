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

    /**
     * VENDOR_HINT_PRE_FLING (Id 0x00001080). Type 4 on sdmmagpie raises the
     * cluster floors for 80 ms and expires on its own, which makes it safe to
     * fire on every scroll-heavy moment.
     */
    private const val VENDOR_HINT_PRE_FLING = 0x00001080
    private const val TYPE_PRE_FLING = 4

    /**
     * VENDOR_HINT_DRAG (Id 0x00001087), Type 1 on sdmmagpie.
     *
     * NOTE: this entry carries `Timeout="0"` in vendor/etc/perf/perfboostsconfig.xml,
     * which the perf HAL reads as "no timeout" - the boost never expires by
     * itself. It must always be paired with [releaseDragBoost], and it is
     * therefore opt-in rather than part of the default game path.
     */
    private const val VENDOR_HINT_DRAG = 0x00001087
    private const val TYPE_DRAG = 1

    /** A negative type releases the hint instead of acquiring it. */
    private const val TYPE_RELEASE = -1

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
        return sendHint(VENDOR_HINT_APP_LAUNCH, packageName, TYPE_GAME_BOOST, "game boost")
    }

    /**
     * Short, self-expiring scroll boost (Type 4, 80 ms). Safe to fire without a
     * matching release: the perf HAL clears it on its own.
     */
    fun preFlingBoost(packageName: String): Boolean {
        return sendHint(VENDOR_HINT_PRE_FLING, packageName, TYPE_PRE_FLING, "pre-fling boost")
    }

    /**
     * Indefinite drag boost (Id 0x1087, Type 1, Timeout=0).
     *
     * The HAL never expires this one, so every successful call **must** be
     * followed by [releaseDragBoost] - otherwise the boost outlives the game
     * and keeps the cluster floors raised until reboot. Returns true when the
     * hint was accepted.
     */
    fun dragBoost(packageName: String): Boolean {
        return sendHint(VENDOR_HINT_DRAG, packageName, TYPE_DRAG, "drag boost")
    }

    /**
     * Release the drag boost acquired by [dragBoost].
     *
     * Releases are sent with a negative type, which is the convention the QTI
     * perf HAL uses for "drop this hint". The return value is advisory: a
     * negative result means the HAL did not acknowledge, and the caller should
     * treat the boost as possibly still active (a mode switch or a reboot is
     * the only guaranteed way out).
     */
    fun releaseDragBoost(): Boolean {
        return sendHint(VENDOR_HINT_DRAG, "", TYPE_RELEASE, "drag boost release")
    }

    /** Release the drag boost for one package, leaving other hints alone. */
    fun releaseDragBoost(packageName: String): Boolean {
        if (packageName.isEmpty()) {
            return releaseDragBoost()
        }
        return sendHint(VENDOR_HINT_DRAG, packageName, TYPE_RELEASE, "drag boost release")
    }

    private fun sendHint(opcode: Int, packageName: String, type: Int, label: String): Boolean {
        // Acquiring a hint needs a package to attribute it to; a release does not.
        if (type >= 0 && packageName.isEmpty()) {
            return false
        }
        if (!isAvailable()) {
            return false
        }
        return try {
            val result = hintMethod?.invoke(instance, opcode, packageName, type, 0) as? Int ?: -1
            SceneLog.i("QtiPerfHints", "$label ($opcode/$type) for '$packageName' -> $result")
            result >= 0
        } catch (ex: Throwable) {
            status = "call failed: " + ex.javaClass.simpleName + ": " + ex.message
            SceneLog.e("QtiPerfHints", "$label hint failed", ex)
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
