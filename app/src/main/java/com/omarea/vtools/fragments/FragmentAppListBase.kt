package com.omarea.vtools.fragments

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.AdapterView.OnItemClickListener
import android.widget.CheckBox
import android.widget.HeaderViewListAdapter
import android.widget.Toast
import com.omarea.Scene
import com.omarea.common.ui.OverScrollListView
import com.omarea.common.ui.ProgressBarDialog
import com.omarea.model.AppInfo
import com.omarea.ui.AdapterAppList
import com.omarea.utils.AppListHelper
import com.omarea.vtools.R
import com.omarea.vtools.databinding.FragmentAppListBinding
import com.omarea.vtools.dialogs.DialogSingleAppOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * Shared implementation of the three app-list tabs (user / system / backup).
 *
 * Those fragments were byte-identical apart from the app-list source, the
 * multi-select options dialog, and the label used in the progress dialog; each
 * fix had to be applied three times, which had already caused small divergences.
 * Subclasses now supply only the differences.
 */
abstract class FragmentAppListBase(
    protected val myHandler: Handler
) : androidx.fragment.app.Fragment() {

    private var _binding: FragmentAppListBinding? = null
    protected val binding get() = _binding!!

    // Adapter currently attached to the list, so its coroutine scope can be cancelled.
    private var currentAppAdapter: AdapterAppList? = null

    // Fragment-scoped so the list load cannot outlive the view hierarchy.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var processBarDialog: ProgressBarDialog
    private lateinit var appListHelper: AppListHelper
    private var appList: ArrayList<AppInfo>? = null
    private var keywords = ""

    /** Tag shown in the loading dialog; also used to identify the tab in logs. */
    protected abstract val progressDialogTag: String

    /** Loads the app list for this tab. Runs on a background-friendly dispatcher. */
    protected abstract fun loadAppList(): ArrayList<AppInfo>

    /** Shows the bulk-options dialog for two or more selected apps. */
    protected abstract fun showMultiAppOptions(activity: Activity, selectedItems: ArrayList<AppInfo>)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        processBarDialog = ProgressBarDialog(activity!!, progressDialogTag)
        appListHelper = AppListHelper(context!!)

        _binding = FragmentAppListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.appList.addHeaderView(this.layoutInflater.inflate(R.layout.list_header_app, null))

        val onItemLongClick = AdapterView.OnItemLongClickListener { parent, _, position, _ ->
            if (position < 1) {
                return@OnItemLongClickListener true
            }
            val adapter = (parent.adapter as HeaderViewListAdapter).wrappedAdapter
            val app = adapter.getItem(position - 1) as AppInfo
            DialogSingleAppOptions(activity!!, app, myHandler).showSingleAppOptions()
            true
        }

        binding.appList.onItemLongClickListener = onItemLongClick
        binding.fabApps.setOnClickListener {
            getSelectedAppShowOptions(activity!!)
        }

        this.setList()
    }

    private fun getSelectedAppShowOptions(activity: Activity) {
        var adapter = binding.appList.adapter
        adapter = (adapter as HeaderViewListAdapter).wrappedAdapter
        val selectedItems = (adapter as AdapterAppList).getSelectedItems()
        if (selectedItems.size == 0) {
            Scene.toast(R.string.app_selected_none, Toast.LENGTH_SHORT)
            return
        }

        if (selectedItems.size == 1) {
            DialogSingleAppOptions(activity, selectedItems.first(), myHandler).showSingleAppOptions()
        } else {
            showMultiAppOptions(activity, selectedItems)
        }
    }

    private fun setList() {
        processBarDialog.showDialog()
        scope.launch {
            appList = loadAppList()
            processBarDialog.hideDialog()
            setListData(appList, binding.appList)
        }
    }

    private fun setListData(dl: ArrayList<AppInfo>?, lv: OverScrollListView) {
        if (dl == null) {
            return
        }
        myHandler.post {
            try {
                val adapterObj = AdapterAppList(context!!, dl, keywords)
                val adapterAppList: WeakReference<AdapterAppList> = WeakReference(adapterObj)
                // Track the live adapter so its icon-load scope can be cancelled in onDestroyView.
                currentAppAdapter?.destroy()
                currentAppAdapter = adapterObj
                lv.adapter = adapterObj
                lv.onItemClickListener = OnItemClickListener { _, itemView, postion, _ ->
                    if (postion == 0) {
                        val checkBox = itemView.findViewById(R.id.select_state_all) as CheckBox
                        checkBox.isChecked = !checkBox.isChecked
                        if (adapterAppList.get() != null) {
                            adapterAppList.get()!!.setSelecteStateAll(checkBox.isChecked)
                            adapterAppList.get()!!.notifyDataSetChanged()
                        }
                    } else {
                        val checkBox = itemView.findViewById(R.id.select_state) as CheckBox
                        checkBox.isChecked = !checkBox.isChecked
                        val all = lv.findViewById<CheckBox>(R.id.select_state_all)
                        if (adapterAppList.get() != null) {
                            all.isChecked = adapterAppList.get()!!.getIsAllSelected()
                        }
                    }
                    binding.fabApps.visibility =
                        if (adapterAppList.get()?.hasSelected() == true) View.VISIBLE else View.GONE
                }
                val all = lv.findViewById<CheckBox>(R.id.select_state_all)
                all.isChecked = false
                binding.fabApps.visibility = View.GONE
            } catch (ex: Exception) {
                // Ignore: the view hierarchy is being torn down.
            }
        }
    }

    public var searchText: String
        get() = keywords
        set(value) {
            if (keywords != value) {
                keywords = value
                setListData(appList, binding.appList)
            }
        }

    fun reloadList() {
        setList()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Cancel pending icon loads so they cannot touch views that are going away.
        currentAppAdapter?.destroy()
        currentAppAdapter = null
        scope.cancel()
        _binding = null
    }
}
