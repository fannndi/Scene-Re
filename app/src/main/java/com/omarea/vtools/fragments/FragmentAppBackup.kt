package com.omarea.vtools.fragments

import android.app.Activity
import android.os.Handler
import com.omarea.model.AppInfo
import com.omarea.utils.AppListHelper
import com.omarea.vtools.dialogs.DialogAppOptions

/**
 * App-list tab showing apps backed up by the "shadow"/backup feature.
 *
 * This tab acts on the whole selection at once (no single-app shortcut), so it
 * overrides [showMultiAppOptions]'s caller path by always using the bulk dialog.
 */
class FragmentAppBackup(myHandler: Handler) : FragmentAppListBase(myHandler) {

    override val progressDialogTag: String = "FragmentAppBackup"

    override fun loadAppList(): ArrayList<AppInfo> {
        return AppListHelper(requireContext()).getShadowAppList()
    }

    override fun showMultiAppOptions(activity: Activity, selectedItems: ArrayList<AppInfo>) {
        DialogAppOptions(activity, selectedItems, myHandler).selectBackupOptions()
    }
}
