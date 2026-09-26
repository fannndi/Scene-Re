package com.omarea.krscript.shortcut

import android.annotation.TargetApi
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import com.omarea.common.shared.ObjectStorage
import com.omarea.krscript.model.NodeInfoBase
import com.omarea.krscript.model.PageNode
import java.util.*

class ActionShortcutManager(private var context: Context) {
    @TargetApi(Build.VERSION_CODES.O)
    public fun addShortcut(intent: Intent, drawable: Drawable, config: NodeInfoBase): Boolean {
        // SerializableExtra cannot be handled when adding a shortcut, so the pageNode info has to be stored by the app itself
        if (intent.hasExtra("page")) {
            val pageNode = intent.getSerializableExtra("page") as PageNode
            intent.putExtra("shortcutId", saveShortcutTarget(pageNode))
            intent.removeExtra("page")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return createShortcutOreo(intent, drawable, config)
        } else {
            return addShortcutNougat(intent, drawable, config)
        }
    }

    private fun addShortcutNougat(intent: Intent, drawable: Drawable, config: NodeInfoBase): Boolean {
        try {
            val shortcut = Intent("com.android.launcher.action.INSTALL_SHORTCUT")
            val id = "addin_" + config.index

            // Shortcut name
            shortcut.putExtra(Intent.EXTRA_SHORTCUT_NAME, config.title)// Shortcut name
            shortcut.putExtra("duplicate", false) // Whether duplicate creation is allowed

            // Shortcut icon
            shortcut.putExtra(Intent.EXTRA_SHORTCUT_ICON, (drawable as BitmapDrawable).bitmap)

            val shortcutIntent = Intent(Intent.ACTION_MAIN)
            shortcutIntent.setClassName(context.getApplicationContext(), intent.component!!.className)
            shortcutIntent.putExtras(intent)

            shortcut.putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortcutIntent)
            shortcutIntent.flags = Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS

            context.sendBroadcast(shortcut)

            return true
        } catch (ex: Exception) {
            return false
        }

    }

    // Store the shortcut's page info object
    private fun saveShortcutTarget(pageNode: PageNode): String {
        val id = System.currentTimeMillis().toString()
        ObjectStorage<PageNode>(context).save(pageNode, id)
        return id
    }

    // Load the shortcut's page info object
    public fun getShortcutTarget(shortcutId: String): PageNode? {
        return ObjectStorage<PageNode>(context).load(shortcutId)
    }

    @TargetApi(Build.VERSION_CODES.O)
    public fun createShortcutOreo(intent: Intent, drawable: Drawable, config: NodeInfoBase): Boolean {
        try {
            val shortcutManager = context.getSystemService(Context.SHORTCUT_SERVICE) as ShortcutManager

            if (shortcutManager.isRequestPinShortcutSupported) {
                val id = "addin_" + config.index
                val shortcutIntent = Intent(Intent.ACTION_MAIN)
                shortcutIntent.setClassName(context.getApplicationContext(), intent.component!!.className)
                shortcutIntent.putExtras(intent)
                shortcutIntent.flags = Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS

                val info = ShortcutInfo.Builder(context, id)
                        .setIcon(Icon.createWithBitmap((drawable as BitmapDrawable).bitmap))
                        .setShortLabel(config.title)
                        .setIntent(shortcutIntent)
                        .setActivity(intent.component!!) // Only the "main" activity - one declaring the Intent#ACTION_MAIN and Intent#CATEGORY_LAUNCHER intent filters - can be the target activity
                        .build()

                val shortcutCallbackIntent = PendingIntent.getBroadcast(context, 0, Intent(), PendingIntent.FLAG_UPDATE_CURRENT)
                if (shortcutManager.isRequestPinShortcutSupported) {
                    val items = shortcutManager.pinnedShortcuts
                    for (item in items) {
                        if (item.id == id) {
                            shortcutManager.updateShortcuts(object : ArrayList<ShortcutInfo>() {
                                init {
                                    add(info)
                                }
                            })
                            return true
                        }
                    }
                    shortcutManager.requestPinShortcut(info, shortcutCallbackIntent.intentSender)
                    return true
                } else {
                    return false
                }
            }
            return true
        } catch (ex: Exception) {
            Log.e("ActionShortcutManager", "" + ex.message)
            // Toast.makeText(context, "Failed to handle the shortcut" + ex.getMessage(), Toast.LENGTH_LONG).show();
            return false
        }
    }
}
