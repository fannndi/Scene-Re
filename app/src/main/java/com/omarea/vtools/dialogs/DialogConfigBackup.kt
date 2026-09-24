package com.omarea.vtools.dialogs

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.omarea.Scene
import com.omarea.common.ui.DialogHelper
import com.omarea.utils.ConfigBackup
import com.omarea.vtools.R
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Export and restore of Scene's configuration archive. */
class DialogConfigBackup(private val context: Activity) {
    @OptIn(DelicateCoroutinesApi::class)
    fun show() {
        val view = context.layoutInflater.inflate(R.layout.dialog_config_backup, null)
        val dialog = DialogHelper.customDialog(context, view)
        val latestLabel = view.findViewById<TextView>(R.id.config_backup_latest)
        val exportButton = view.findViewById<Button>(R.id.config_backup_export)
        val restoreButton = view.findViewById<Button>(R.id.config_backup_restore)

        fun refreshLatest() {
            latestLabel.text = ConfigBackup.latest() ?: context.getString(R.string.config_backup_none)
        }
        refreshLatest()

        exportButton.setOnClickListener {
            exportButton.isEnabled = false
            GlobalScope.launch {
                val path = withContext(Dispatchers.IO) { ConfigBackup.export(context) }
                exportButton.isEnabled = true
                Scene.toast(
                    if (path == null) context.getString(R.string.config_backup_failed)
                    else context.getString(R.string.config_backup_done, path)
                )
                refreshLatest()
            }
        }

        restoreButton.setOnClickListener {
            val latest = ConfigBackup.latest()
            if (latest == null) {
                Scene.toast(context.getString(R.string.config_backup_none))
                return@setOnClickListener
            }
            DialogHelper.confirm(context, context.getString(R.string.config_backup_restore), latest, {
                restoreButton.isEnabled = false
                GlobalScope.launch {
                    val ok = withContext(Dispatchers.IO) { ConfigBackup.restore(context, latest) }
                    restoreButton.isEnabled = true
                    dialog.dismiss()
                    Scene.toast(
                        if (ok) context.getString(R.string.config_backup_restored)
                        else context.getString(R.string.config_backup_failed)
                    )
                }
            })
        }

        view.findViewById<View>(R.id.config_backup_desc).setOnClickListener { }
    }
}
