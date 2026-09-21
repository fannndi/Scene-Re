package com.omarea.scene_mode

import android.app.Notification.FLAG_AUTO_CANCEL
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

// notification listener (game DND)
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
                // Log.e("vtool-disnotice", "accessibility service not started")
                return
            } else {
                /*
                if (sbn.isOngoing) {
                    // running in the foreground!!?
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
