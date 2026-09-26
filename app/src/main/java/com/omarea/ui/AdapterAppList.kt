package com.omarea.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import com.omarea.library.basic.AppInfoLoader
import com.omarea.model.AppInfo
import com.omarea.vtools.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.ArrayList
import java.util.HashMap
import kotlin.Comparator

@OptIn(DelicateCoroutinesApi::class)
class AdapterAppList(private val context: Context, apps: ArrayList<AppInfo>, private var keywords: String = "") : BaseAdapter() {
    private val list: ArrayList<AppInfo>?
    private val appInfoLoader = AppInfoLoader(context)

    @SuppressLint("UseSparseArrays")
    var states = HashMap<Int, Boolean>()

    //private val mImageCache: LruCache<String, Drawable> = LruCache(20)

    /**
     * Coroutine scope owned by this adapter (replaces GlobalScope).
     *
     * GlobalScope kept a coroutine running after the owning Activity was gone and
     * started a new untracked job on every getView during a fling. The previous
     * code also stored the recycled ViewHolder in a *shared instance field*, so a
     * getView for row B could overwrite it while row A's icon load was still in
     * flight. Cancel this from the owning view's onDestroy via [destroy].
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Cancels all pending icon loads. Call from the owning view's onDestroy. */
    fun destroy() {
        scope.cancel()
    }

    fun setSelecteStateAll(selected: Boolean = true) {
        for (item in states) {
            states[item.key] = selected
        }
    }

    fun getIsAllSelected(): Boolean {
        var count = 0
        for (item in states) {
            if (item.value == true) {
                count++
            }
        }
        return count == this.list!!.size
    }

    fun hasSelected(): Boolean {
        return this.states.filter { it.value == true }.isNotEmpty()
    }

    init {
        this.list = sortAppList(filterAppList(apps, keywords))
        for (i in this.list.indices) {
            states[i] = !(this.list[i].stateTags == null || !this.list[i].selected)
        }
    }

    override fun getCount(): Int {
        return list?.size ?: 0
    }

    override fun getItem(position: Int): AppInfo {
        return list!![position]
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    private fun keywordSearch(item: AppInfo, text: String): Boolean {
        return item.packageName.lowercase().contains(text)
                || item.appName.lowercase().contains(text)
                || item.path.toString().lowercase().contains(text)
    }

    private fun filterAppList(appList: ArrayList<AppInfo>, keywords: String): ArrayList<AppInfo> {
        val text = keywords.lowercase()
        if (text.isEmpty())
            return appList
        return ArrayList(appList.filter { item ->
            keywordSearch(item, text)
        })
    }

    private fun sortAppList(list: ArrayList<AppInfo>): ArrayList<AppInfo> {
        list.sortWith(Comparator { l, r ->
            val les = l.stateTags.toString()
            val res = r.stateTags.toString()
            when {
                les < res -> -1
                les > res -> 1
                else -> {
                    val lp = l.packageName.toString()
                    val rp = r.packageName.toString()
                    when {
                        lp < rp -> -1
                        lp > rp -> 1
                        else -> 0
                    }
                }
            }
        })
        return list
    }

    fun getSelectedItems(): ArrayList<AppInfo> {
        val states = states
        val selectedItems = states.keys
                .filter { states[it] == true }
                .mapTo(ArrayList()) { getItem(it) }

        if (selectedItems.size == 0) {
            return ArrayList()
        }
        return selectedItems
    }

    override fun getView(position: Int, view: View?, parent: ViewGroup): View {
        var convertView = view
        val context = parent.context
        // Local holder: each row keeps its own binding for the duration of this call.
        val viewHolder: ViewHolder
        if (convertView == null) {
            viewHolder = ViewHolder()
            convertView = View.inflate(context, R.layout.list_item_app, null)
            viewHolder.run {
                itemTitle = convertView!!.findViewById(R.id.ItemTitle)
                enabledStateText = convertView.findViewById(R.id.ItemEnabledStateText)
                itemText = convertView.findViewById(R.id.ItemText)
                imgView = convertView.findViewById(R.id.ItemIcon)
                itemChecke = convertView.findViewById(R.id.select_state)
                // itemPath = convertView.findViewById(R.id.ItemPath)
                imgView!!.setTag(getItem(position).packageName)
            }
            convertView.tag = viewHolder
        } else {
            viewHolder = convertView.tag as ViewHolder
        }
        viewHolder?.run {
            val item = getItem(position)
            itemTitle?.text = SearchHighlighter.highlight(item.appName, keywords)
            itemText?.text = SearchHighlighter.highlight(item.packageName, keywords)

            val id = item.path
            this.appPath = id
            val targetImageView = imgView
            if (targetImageView != null) {
                scope.launch {
                    val icon = appInfoLoader.loadIcon(item).await()
                    // Re-check the row identity AND that this holder is still bound
                    // to the same package before touching the ImageView.
                    if (icon != null && appPath == id && targetImageView.getTag() == item.packageName) {
                        targetImageView.setImageDrawable(icon)
                    }
                }
            }

            enabledStateText?.run {
                if (item.stateTags.isNullOrEmpty()) {
                    text = ""
                    visibility = View.GONE
                } else {
                    text = item.stateTags
                    visibility = View.VISIBLE
                }
            }

            // Add a check-change listener for the checkbox and store its state per position in a HashMap
            itemChecke?.setOnCheckedChangeListener { _, isChecked ->
                states[position] = isChecked
            }
            // Read the state back from the HashMap and apply it to the checkbox at that list position
            itemChecke?.setChecked(states[position] == true)

            // viewHolder?.itemPath?.text = item.path
        }

        return convertView!!
    }

    inner class ViewHolder {
        internal var appPath: CharSequence? = null

        internal var itemTitle: TextView? = null
        internal var itemChecke: CheckBox? = null
        internal var imgView: ImageView? = null
        internal var itemText: TextView? = null
        internal var enabledStateText: TextView? = null
        internal var itemPath: TextView? = null
    }
}
