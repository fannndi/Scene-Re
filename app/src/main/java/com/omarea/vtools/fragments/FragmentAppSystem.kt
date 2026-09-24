package com.omarea.vtools.fragments

import android.app.Activity
import android.os.Handler
import com.omarea.model.AppInfo
import com.omarea.utils.AppListHelper
import com.omarea.vtools.dialogs.DialogAppOptions

/** App-list tab showing system apps. */
class FragmentAppSystem(myHandler: Handler) : FragmentAppListBase(myHandler) {

    override val progressDialogTag: String = "FragmentAppSystem"

    override fun loadAppList(): ArrayList<AppInfo> {
        return AppListHelper(requireContext()).getSystemAppList()
    }

    override fun showMultiAppOptions(activity: Activity, selectedItems: ArrayList<AppInfo>) {
        DialogAppOptions(activity, selectedItems, myHandler).selectSystemAppOptions()
    }
}
