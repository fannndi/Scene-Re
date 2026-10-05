package com.omarea.scene_mode

import android.content.Context
import com.omarea.common.shared.FileWrite
import com.omarea.common.shell.KeepShellPublic
import com.omarea.data.EventType
import com.omarea.data.IEventReceiver
import com.omarea.store.SpfConfig
import java.io.File

/**
 * 息屏/亮屏调频钩子
 *
 * - 息屏：应用 profiles/screen_off.json（省电）
 * - 亮屏：重新应用当前模式（vtools.powercfg）
 *
 * 仅在应用内置配置（非外部 /data/powercfg.sh、非自定义模式）时生效。
 */
class PowerCfgScreenHook(private val context: Context) : IEventReceiver {
    override fun eventFilter(eventType: EventType): Boolean {
        return eventType == EventType.SCREEN_OFF || eventType == EventType.SCREEN_ON
    }

    override fun onReceive(eventType: EventType, data: HashMap<String, Any>?) {
        val source = ModeSwitcher.getCurrentSource()
        if (source != ModeSwitcher.SOURCE_SCENE_ACTIVE && source != ModeSwitcher.SOURCE_SCENE_CONSERVATIVE) {
            return
        }

        val provider = FileWrite.getPrivateFilePath(context, "powercfg.sh")
        if (!File(provider).exists()) {
            return
        }

        val action = if (eventType == EventType.SCREEN_OFF) "screen_off" else "screen_on"
        val tier = context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
            .getString(SpfConfig.GLOBAL_SPF_PROFILE_TIER, SpfConfig.GLOBAL_SPF_PROFILE_TIER_DEFAULT)
        Thread {
            try {
                KeepShellPublic.secondaryKeepShell.doCmdSync(
                    "export profile_tier=$tier\n" +
                            "export top_app=''\n" +
                            "sh $provider '$action' > /dev/null 2>&1"
                )
            } catch (ex: Exception) {
            }
        }.start()
    }

    override val isAsync: Boolean
        get() = false

    override fun onSubscribe() {
    }

    override fun onUnsubscribe() {
    }
}
