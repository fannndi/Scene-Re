package com.omarea.scene_mode.service

import android.app.Notification.FLAG_AUTO_CANCEL
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.omarea.scene_mode.SceneMode

// 通知监听（游戏勿扰）
class NotificationListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) {
            return
        }
        /*
        if (sbn.isOngoing) {
            Toast.makeText(this, sbn.id.toString() + " is running in the background...", Toast.LENGTH_SHORT).show()
            return
        }
        */
        if (sbn.isClearable) {
            val instance = SceneMode.getCurrentInstance()
            if (instance == null) {
                // Log.e("vtool-disnotice", "辅助服务未启动")
                return
            } else {
                /*
                if (sbn.isOngoing) {
                    // 正在前台运行！！？
                    cancelNotification(sbn.key)
                } else {
                    if (instance.onNotificationPosted()) {
                        cancelNotification(sbn.key)
                    }
                }
                */
                if (instance.onNotificationPosted()) {
                    try {
                        sbn.notification.flags.and(FLAG_AUTO_CANCEL)
                        cancelNotification(sbn.key)
                    } catch (ex: Exception) {

                    }
                }
            }
        }
    }
}
