package com.omarea.scene_mode

import android.content.Context
import com.omarea.Scene
import com.omarea.common.shared.FileWrite
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.RootFile
import com.omarea.library.shell.PlatformUtils
import com.omarea.store.SpfConfig
import java.io.File

class CpuConfigInstaller {
    val rootDir = "powercfg"

    private fun getPowerCfgDir(): String {
        return rootDir + "/" + PlatformUtils().getCPUName()
    }

    // 安装应用内自带的配置
    fun installOfficialConfig(context: Context, afterCmds: String = "", active: Boolean = false): Boolean {
        if (!dynamicSupport(context)) {
            return false
        }
        try {
            val dir = getPowerCfgDir()
            val powercfg = FileWrite.writePrivateShellFile(dir + "/powercfg.sh", "powercfg.sh", context)
            val powercfgBase = FileWrite.writePrivateShellFile(dir + "/powercfg-base.sh", "powercfg-base.sh", context)
            // 工具函数
            FileWrite.writePrivateShellFile(dir + "/powercfg-utils.sh", "powercfg-utils.sh", context)
            // 每个模式的调优数据（默认版 + 用户编辑版）
            installProfiles(context, dir)

            if (powercfg == null) {
                return false
            } else {
                File(powercfg).run {
                    setExecutable(true, false)
                    setWritable(true)
                    setReadable(true)
                }
                if (powercfgBase != null) {
                    File(powercfgBase).run {
                        setExecutable(true, false)
                        setWritable(true)
                        setReadable(true)
                    }
                }

                ModeSwitcher().setCurrentPowercfg("")
                if (!afterCmds.isEmpty()) {
                    KeepShellPublic.doCmdSync(afterCmds)
                }
                val config =  context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE).edit()
                config.putString(SpfConfig.GLOBAL_SPF_PROFILE_SOURCE, (
                        if (active) {
                            ModeSwitcher.SOURCE_SCENE_ACTIVE
                        } else {
                            ModeSwitcher.SOURCE_SCENE_CONSERVATIVE
                        }
                        )
                ).apply()
                return true
            }
        } catch (ex: Exception) {
        }
        return false
    }

    // 安装每个模式的调优数据（profiles/*.json）
    // - profiles/default/ 始终随应用刷新（内置默认值）
    // - profiles/ 是用户编辑版：不主动生成；若与内置默认完全一致则清理
    //   （历史版本曾在此生成副本，会导致“已自定义”误判）
    private fun installProfiles(context: Context, assetDir: String) {
        try {
            val assetManager = context.assets
            val names = assetManager.list("$assetDir/profiles") ?: return
            for (name in names) {
                if (!name.endsWith(".json")) {
                    continue
                }
                FileWrite.writePrivateFile(assetManager, "$assetDir/profiles/$name", "profiles/default/$name", context)

                val userFile = File(FileWrite.getPrivateFilePath(context, "profiles/$name"))
                if (userFile.exists()) {
                    try {
                        val defaultFile = File(FileWrite.getPrivateFilePath(context, "profiles/default/$name"))
                        if (defaultFile.exists() && userFile.readText() == defaultFile.readText()) {
                            userFile.delete()
                        }
                    } catch (ex: Exception) {
                    }
                }
            }
        } catch (ex: Exception) {
        }
    }

    // 尝试更新调度配置文件（目前仅支持自动更新内置的调度文件）
    fun applyConfigNewVersion(context: Context) {
        if (!outsideConfigInstalled()) {
            val config = Scene.context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
            val source = config.getString(SpfConfig.GLOBAL_SPF_PROFILE_SOURCE, ModeSwitcher.SOURCE_UNKNOWN)
            when (source) {
                ModeSwitcher.SOURCE_SCENE_ACTIVE -> {
                    installOfficialConfig(context, "", true)
                }
                ModeSwitcher.SOURCE_SCENE_CONSERVATIVE -> {
                    installOfficialConfig(context, "", false)
                }
            }
        }
    }

    // 校验编码
    fun configCodeVerify() {
        try {
            val cmd = StringBuilder()
            cmd.append("if [[ -f ${ModeSwitcher.OUTSIDE_POWER_CFG_PATH} ]]; then \n")
            cmd.append("busybox sed -i 's/\\r//' ${ModeSwitcher.OUTSIDE_POWER_CFG_PATH};\n")
            cmd.append("chmod 0775 ${ModeSwitcher.OUTSIDE_POWER_CFG_PATH};\n")
            cmd.append("fi;\n")
            cmd.append("if [[ -f ${ModeSwitcher.OUTSIDE_POWER_CFG_BASE} ]]; then \n")
            cmd.append("busybox sed -i 's/\\r//' ${ModeSwitcher.OUTSIDE_POWER_CFG_BASE};\n")
            cmd.append("chmod 0777 ${ModeSwitcher.OUTSIDE_POWER_CFG_BASE};\n")
            cmd.append("fi;\n")
            KeepShellPublic.doCmdSync(cmd.toString())
        } catch (ex: Exception) {
        }
    }

    // 检查是否支持动态响应
    fun dynamicSupport(context: Context): Boolean {
        val cpuName = PlatformUtils().getCPUName()
        val names = context.assets.list(rootDir)
        if (names != null) {
            for (i in names.indices) {
                if (names[i].equals(cpuName)) {
                    return true
                }
            }
        }
        return false;
    }

    // 是否已经安装外部配置文件
    fun outsideConfigInstalled(): Boolean {
        return RootFile.fileNotEmpty(ModeSwitcher.OUTSIDE_POWER_CFG_PATH)
    }

    // 是否已经安装内部配置文件
    fun insideConfigInstalled(): Boolean {
        return File(FileWrite.getPrivateFilePath(Scene.context, "powercfg.sh")).exists()
    }
}
