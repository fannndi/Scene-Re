package com.omarea.scene_mode

import android.content.Context
import com.omarea.Scene
import com.omarea.common.shared.FileWrite
import com.omarea.common.shell.KeepShellPublic
import com.omarea.library.basic.ScreenState
import com.omarea.store.ProfileStore
import java.io.File

/**
 * Profile Service 守护模块
 *
 * 开启时：
 *  - 关闭会覆盖调优的系统服务（MIUI booster / QTI perf HAL / mi_thermald / PerformanceMode）
 *  - 周期巡检调频上限，被覆盖且温度正常时重新应用当前模式
 *  - 过热时临时恢复 mi_thermald（软保护；内核 step_wise 110/120°C 仍是最终防线）
 *
 * 关闭时：
 *  - 恢复被关闭的服务
 *  - 应用 ROM/内核默认（powercfg.sh stock）
 */
object ProfileServiceGuard {
    private const val PROP_BOOSTER = "persist.sys.enable_miui_booster"
    private const val PERFORMANCE_MODE_PKG = "com.qualcomm.qti.performancemode"

    private const val GUARD_INTERVAL_MS = 30_000L
    // 温度单位：毫摄氏度（sysfs 原始值）
    private const val THERMAL_RESTART_TEMP = 72000
    private const val THERMAL_RESUME_TEMP = 60000

    @Volatile
    private var running = false
    private var guardThread: Thread? = null

    @Volatile
    private var thermalRestored = false

    fun enable(context: Context) {
        val appContext = context.applicationContext
        Thread {
            applySuppression(true)
        }.start()
        startGuard(appContext)
    }

    fun disable(context: Context) {
        val appContext = context.applicationContext
        stopGuard()
        Thread {
            applySuppression(false)
            applyStock(appContext)
        }.start()
    }

    // ---------------- 服务抑制 ----------------

    private fun applySuppression(on: Boolean) {
        val cmds = StringBuilder()
        if (on) {
            // MIUI booster（perf lock 来源）
            cmds.append("setprop ").append(PROP_BOOSTER).append(" 0\n")
            cmds.append("setprop ctl.stop miuibooster\n")
            // QTI perf HAL
            cmds.append("setprop ctl.stop vendor.perfservice\n")
            cmds.append("setprop ctl.stop perf-hal-2-2\n")
            // MIUI thermal daemon（内核 step_wise 仍是最后防线）
            cmds.append("setprop ctl.stop mi_thermald\n")
            // Game Booster
            cmds.append("settings put secure gb_boosting 0\n")
            cmds.append("settings put secure pref_open_game_booster 0\n")
            // QTI PerformanceMode
            cmds.append("pm disable-user --user 0 ").append(PERFORMANCE_MODE_PKG).append("\n")
        } else {
            cmds.append("setprop ").append(PROP_BOOSTER).append(" 1\n")
            cmds.append("setprop ctl.start miuibooster\n")
            cmds.append("setprop ctl.start vendor.perfservice\n")
            cmds.append("setprop ctl.start perf-hal-2-2\n")
            cmds.append("setprop ctl.start mi_thermald\n")
            cmds.append("pm enable --user 0 ").append(PERFORMANCE_MODE_PKG).append("\n")
        }
        try {
            KeepShellPublic.secondaryKeepShell.doCmdSync(cmds.toString())
        } catch (ex: Exception) {
        }
    }

    // ---------------- ROM 默认恢复 ----------------

    private fun applyStock(context: Context) {
        try {
            val provider = FileWrite.getPrivateFilePath(context, "powercfg.sh")
            if (File(provider).exists()) {
                KeepShellPublic.secondaryKeepShell.doCmdSync("sh $provider stock > /dev/null 2>&1")
            }
            ModeSwitcher().setCurrentPowercfg("")
        } catch (ex: Exception) {
        }
    }

    // ---------------- 巡检 ----------------

    private fun startGuard(context: Context) {
        if (running) {
            return
        }
        running = true
        guardThread = Thread {
            while (running) {
                try {
                    Thread.sleep(GUARD_INTERVAL_MS)
                    if (running) {
                        guardTick(context)
                    }
                } catch (ex: InterruptedException) {
                    break
                } catch (ex: Exception) {
                }
            }
        }.apply { isDaemon = true }
        guardThread?.start()
    }

    private fun stopGuard() {
        running = false
        guardThread?.interrupt()
        guardThread = null
        thermalRestored = false
    }

    private fun guardTick(context: Context) {
        val temp = cpuTemperature()

        // 过热：临时恢复 mi_thermald；降温后再次停用
        if (temp != null) {
            if (!thermalRestored && temp >= THERMAL_RESTART_TEMP) {
                thermalRestored = true
                KeepShellPublic.secondaryKeepShell.doCmdSync("setprop ctl.start mi_thermald")
                Scene.toast("Profile Service: CPU " + (temp / 1000) + "°C — thermal protection re-enabled")
            } else if (thermalRestored && temp <= THERMAL_RESUME_TEMP) {
                thermalRestored = false
                KeepShellPublic.secondaryKeepShell.doCmdSync("setprop ctl.stop mi_thermald")
            }
        }
        if (thermalRestored) {
            return
        }

        // 息屏/锁屏时不巡检（screen_off 配置生效中）
        if (!ScreenState(context).isScreenOn()) {
            return
        }

        val mode = ModeSwitcher.getCurrentPowerMode()
        if (mode.isEmpty() || mode == ProfileStore.SCREEN_OFF) {
            return
        }

        val profile = ProfileStore(context).merged(mode)
        val expectedLittle = profile.optInt("little_max", 0)
        val expectedBig = profile.optInt("big_max", 0)
        if (expectedLittle <= 0 || expectedBig <= 0) {
            return
        }

        val currentLittle = readFreq("/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq")
        val currentBig = readFreq("/sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq")
        if (currentLittle != expectedLittle || currentBig != expectedBig) {
            // 被系统服务覆盖：重新应用当前模式
            ModeSwitcher().executePowercfgMode(mode, Scene.thisPackageName)
        }
    }

    private fun readFreq(path: String): Int {
        return try {
            File(path).readText().trim().toIntOrNull() ?: -1
        } catch (ex: Exception) {
            -1
        }
    }

    private fun cpuTemperature(): Int? {
        return try {
            File("/sys/class/thermal/thermal_zone18/temp").readText().trim().toIntOrNull()
        } catch (ex: Exception) {
            null
        }
    }
}
