package com.omarea.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import com.omarea.common.ui.OverScrollGridView
import com.omarea.library.basic.AppInfoLoader
import com.omarea.model.AppInfo
import com.omarea.vtools.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.*

class AdapterFreezeApp(private val context: Context, private var apps: ArrayList<AppInfo>) : BaseAdapter(), Filterable {
    private val appIconLoader = AppInfoLoader(context)
    private var filter: Filter? = null
    internal var filterApps: ArrayList<AppInfo> = apps
    private val mLock = Any()

    /**
     * Scope owned by this adapter (replaces GlobalScope). The grid launched one
     * untracked icon-load job per getView; during a fling that queued thousands
     * of jobs which outlived the Activity. Cancel from the owner via [destroy].
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Cancels pending icon loads. Call from the owning view's onDestroy. */
    fun destroy() {
        scope.cancel()
    }

    private class ArrayFilter(private var adapter: AdapterFreezeApp) : Filter() {
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            val values = (results?.values as? List<*>)?.filterIsInstance<AppInfo>()
                    ?: emptyList()
            adapter.filterApps = ArrayList(values)
            if (values.isNotEmpty()) {
                adapter.notifyDataSetChanged()
            } else {
                adapter.notifyDataSetInvalidated()
            }
        }

        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val results = FilterResults()
            val prefix: String = if (constraint == null) "" else constraint.toString()

            if (prefix.isEmpty()) {
                val list: ArrayList<AppInfo>
                synchronized(adapter.mLock) {
                    list = ArrayList<AppInfo>(adapter.apps)
                }
                results.values = list
                results.count = list.size
            } else {
                val prefixString = prefix.lowercase(Locale.getDefault())

                val values: ArrayList<AppInfo>
                synchronized(adapter.mLock) {
                    values = ArrayList<AppInfo>(adapter.apps)
                }

                val count = values.size
                val newValues = ArrayList<AppInfo>()

                for (i in 0 until count) {
                    val value = values[i]
                    val valueText = value.appName.lowercase(Locale.getDefault())

                    if (valueText.contains(prefixString)) {
                        newValues.add(value)
                    } else {
                        val words = valueText.split(" ".toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()
                        val wordCount = words.size

                        for (k in 0 until wordCount) {
                            if (words[k].contains(prefixString)) {
                                newValues.add(value)
                                break
                            }
                        }
                    }
                }

                results.values = newValues
                results.count = newValues.size
            }

            return results
        }
    }

    override fun getFilter(): Filter {
        if (filter == null) {
            filter = ArrayFilter(this)
        }
        return filter!!
    }

    init {
        filterApps.sortBy { !it.enabled || it.suspended }
    }

    override fun getCount(): Int {
        return filterApps.size + 1
    }

    override fun getItem(position: Int): AppInfo {
        if (position < filterApps.size) {
            return filterApps[position]
        } else {
            // Synthetic list item for the "add" button at the end of the list
            return AppInfo.getItem().apply {
                packageName = "plus"
                appName = "Add app"
            }
        }
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    override fun getView(position: Int, view: View?, parent: ViewGroup): View {
        var convertView = view
        if (convertView == null) {
            convertView = View.inflate(context, R.layout.list_item_freeze_app, null)
        }
        updateRow(position, convertView!!)
        return convertView
    }

    fun updateRow(position: Int, listView: OverScrollGridView, appInfo: AppInfo) {
        try {
            val visibleFirst = listView.firstVisiblePosition
            val visibleLast = listView.lastVisiblePosition

            if (position in visibleFirst..visibleLast) {
                filterApps[position] = appInfo
                val view = listView.getChildAt(position - visibleFirst)
                updateRow(position, view)
            }
        } catch (ex: Exception) {

        }
    }

    fun updateRow(position: Int, convertView: View) {
        val item = getItem(position)
        val viewHolder = ViewHolder()
        val packageName = item.packageName
        viewHolder.packageName = packageName
        viewHolder.itemTitle = convertView.findViewById(R.id.ItemTitle)
        viewHolder.imgView = convertView.findViewById(R.id.ItemIcon)
        viewHolder.imgView!!.tag = getItem(position).packageName
        viewHolder.itemTitle!!.text = item.appName
        viewHolder.imgView!!.alpha = if (item.enabled && !item.suspended) 1f else 0.3f

        if (item.packageName == "plus") {
            viewHolder.imgView!!.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.icon_add_app))
        } else {
            val targetImageView = viewHolder.imgView
            if (targetImageView != null) {
                scope.launch {
                    val icon = appIconLoader.loadIcon(item.packageName).await()
                    // Re-check the row identity before touching the ImageView: this
                    // holder may already have been rebound to another app.
                    if (icon != null && targetImageView.tag == packageName) {
                        targetImageView.setImageDrawable(icon)
                    }
                }
            }
        }
    }

    inner class ViewHolder {
        internal var packageName: String? = null

        internal var itemTitle: TextView? = null
        internal var imgView: ImageView? = null
    }
}
