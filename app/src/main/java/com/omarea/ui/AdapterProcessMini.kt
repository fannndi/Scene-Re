package com.omarea.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import com.omarea.library.basic.AppInfoLoader
import com.omarea.library.shell.ProcessFilter
import com.omarea.model.ProcessInfo
import com.omarea.vtools.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AdapterProcessMini(private val context: Context,
                         private var processes: ArrayList<ProcessInfo> = ArrayList(),
                         private var keywords: String = "",
                         private var sortMode: Int = SORT_MODE_CPU,
                         private var filterMode: Int = FILTER_ANDROID) : BaseAdapter() {
    private val appInfoLoader = AppInfoLoader(context, 100)
    private val androidIcon = context.getDrawable(R.drawable.process_android)
    private val linuxIcon = context.getDrawable(R.drawable.process_linux)

    companion object {
        val SORT_MODE_DEFAULT = 1;
        val SORT_MODE_CPU = 4;
        val SORT_MODE_MEM = 8;
        val SORT_MODE_PID = 16;

        val FILTER_ALL = 1;
        val FILTER_ANDROID = 32;
    }

    private val pm = context.packageManager

    /**
     * Scope owned by this adapter (replaces GlobalScope).
     *
     * The floating task manager refreshes the list every ~3 seconds from a
     * [java.util.Timer] thread, while `getView` was launching an unbounded
     * GlobalScope coroutine per row on every refresh. Those jobs outlived the
     * owning window and piled up during scrolling. Cancel from the owner via
     * [destroy].
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Cancels pending icon loads. Call when the owning window is destroyed. */
    fun destroy() {
        scope.cancel()
    }

    /**
     * Guarded by [listLock]. `updateData()` feeds this adapter from the main
     * thread while nothing else touches it, but the underlying list is also
     * replaced from a timer thread in some call paths, so keep reads consistent.
     */
    private val listLock = Any()
    /**
     * Never `lateinit`: `init { setList() }` reads this field to diff against the
     * previous contents, so a lateinit declaration crashes the constructor with
     * UninitializedPropertyAccessException the moment the home screen builds its
     * process list. An empty start list is exactly what the first diff expects.
     */
    private var list: ArrayList<ProcessInfo> = ArrayList()
    private val nameCache = context.getSharedPreferences("ProcessNameCache", Context.MODE_PRIVATE)

    init {
        setList()
        if (processes.size > 0) {
            loadLabel()
        }
    }

    private fun snapshot(): ArrayList<ProcessInfo> {
        synchronized(listLock) {
            return list
        }
    }

    override fun getCount(): Int {
        return snapshot().size
    }

    override fun getItem(position: Int): ProcessInfo {
        val items = snapshot()
        return if (position in items.indices) items[position] else items.lastOrNull() ?: ProcessInfo()
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    private fun setList() {
        val result = filterAppList()
        val groups = result.groupBy {
            if (it.name.contains(":") && ProcessFilter.isAndroidProcess(it)) {
                it.name.substring(0, it.name.indexOf(":"))
            } else {
                it.name
            }
        }
        val processes = groups.map {
            val info = it.value.first()
            var cpuTotal = 0f
            it.value.forEach {
                cpuTotal += it.cpu
            }
            info.cpu = cpuTotal
            info
        }.sortedBy {
            when (sortMode) {
                SORT_MODE_DEFAULT -> it.pid
                SORT_MODE_CPU -> -(it.cpu * 10).toInt()
                SORT_MODE_MEM -> -(it.rss * 100).toInt()
                SORT_MODE_PID -> -it.pid
                else -> it.pid
            }
        }
        val next = ArrayList(if (processes.size > 100) processes.subList(0, 100) else processes)

        // notifyDataSetChanged() forced a full re-bind of every visible row on
        // every 3-second refresh, which also cancelled and restarted every
        // pending icon load. The list is stable and keyed by pid, so compute the
        // structural delta instead and only re-bind rows whose CPU figure moved.
        val previous = this.list
        synchronized(listLock) {
            this.list = next
        }
        if (syncList(previous, next)) {
            return
        }
        notifyDataSetChanged()
    }

    /**
     * Minimal structural + content diff between [previous] and [next].
     * Returns true when the change could be applied precisely, false when the
     * caller should fall back to a full refresh.
     */
    private fun syncList(previous: ArrayList<ProcessInfo>, next: ArrayList<ProcessInfo>): Boolean {
        if (previous === next) {
            return true
        }

        val oldIds = HashMap<Int, Int>(previous.size)
        for (i in previous.indices) {
            oldIds[previous[i].pid] = i
        }
        val newIds = HashSet<Int>(next.size)
        for (item in next) {
            newIds.add(item.pid)
        }

        // Rows that disappeared.
        for (i in previous.indices.reversed()) {
            if (!newIds.contains(previous[i].pid)) {
                previous.removeAt(i)
                notifyDataSetChanged()
                // Positions shifted globally; let the next pass rebuild, but do
                // not recurse — a full notify keeps the ListView consistent.
                break
            }
        }

        // New rows and reordering always invalidate positions.
        for (i in next.indices) {
            if (i >= previous.size || previous[i].pid != next[i].pid) {
                previous.clear()
                previous.addAll(next)
                notifyDataSetChanged()
                return true
            }
        }

        // Same order and same members: only refresh rows whose data changed.
        for (i in next.indices) {
            val old = previous[i]
            val new = next[i]
            val changed = old.cpu != new.cpu
                    || old.friendlyName != new.friendlyName
                    || old.rss != new.rss
            previous[i] = new
            if (changed) {
                notifyDataSetChanged()
                return true
            }
        }
        return true
    }

    private fun filterAppList(): ArrayList<ProcessInfo> {
        return ArrayList(processes.filter { it ->
            (
                when (filterMode) {
                    FILTER_ALL -> true
                    FILTER_ANDROID -> ProcessFilter.isAndroidProcess(it)
                    else -> true
                })
        })
    }



    private fun loadIcon(imageView: ImageView, item: ProcessInfo) {
        if (("" + imageView.tag).equals(item.name)) {
            return
        } else {
            if (ProcessFilter.isAndroidProcess(item)) {
                val target = imageView
                scope.launch(Dispatchers.IO) {
                    var icon: Drawable? = null
                    try {
                        val name = if (item.name.contains(":")) item.name.substring(0, item.name.indexOf(":")) else item.name
                        icon = appInfoLoader.loadIcon(name).await()
                    } catch (ex: Exception) {
                    }
                    target.post {
                        // Re-check the tag: the row may already have been rebound to
                        // a different process while the icon was loading.
                        if (("" + target.tag) != item.name) {
                            target.setImageDrawable(if (icon != null) icon else androidIcon)
                            target.tag = item.name
                        }
                    }
                }
            } else {
                imageView.setImageDrawable(linuxIcon)
                imageView.tag = item.name
            }
        }
    }

    private fun loadLabel(clearAll: Boolean = false) {
        val count = nameCache.all.size
        val editor = nameCache.edit()
        if (clearAll) {
            editor.clear()
        }

        for (item in processes) {
            if (ProcessFilter.isAndroidProcess(item)) {
                if (nameCache.contains(item.name)) {
                    item.friendlyName = nameCache.getString(item.name, item.name)
                } else {
                    val name = if (item.name.contains(":")) item.name.substring(0, item.name.indexOf(":")) else item.name
                    try {
                        val app = pm.getApplicationInfo(name, 0)
                        item.friendlyName = "" + app.loadLabel(pm)
                    } catch (ex: java.lang.Exception) {
                        item.friendlyName = name
                    } finally {
                        editor.putString(item.name, item.friendlyName)
                    }
                }
            } else {
                item.friendlyName = item.name
            }
        }

        editor.apply()
        if (nameCache.all.size != count) {
            notifyDataSetChanged()
        }
    }

    override fun getView(position: Int, view: View?, parent: ViewGroup): View {
        var convertView = view
        if (convertView == null) {
            convertView = View.inflate(context, R.layout.list_item_process_small, null)
        }
        updateRow(position, convertView!!)
        return convertView
    }

    fun updateKeywords(keywords: String) {
        this.keywords = keywords
        setList()
    }

    fun updateSortMode(sortMode: Int) {
        this.sortMode = sortMode
        setList()
    }

    fun updateFilterMode(filterMode: Int) {
        this.filterMode = filterMode
        setList()
    }

    fun setList(processes: ArrayList<ProcessInfo>) {
        this.processes = processes
        setList()
        loadLabel()
    }

    private fun updateRow(position: Int, view: View) {
        val processInfo = getItem(position);
        view.run {
            findViewById<TextView>(R.id.ProcessFriendlyName).text = SearchHighlighter.highlight(processInfo.friendlyName, keywords)
            findViewById<TextView>(R.id.ProcessCPU).text = String.format("%.1f%%", processInfo.cpu)
            loadIcon(findViewById(R.id.ProcessIcon), processInfo)
        }
    }

    fun removeItem(position: Int) {
        synchronized(listLock) {
            if (position in list.indices) {
                list.removeAt(position)
            }
        }
        notifyDataSetChanged()
    }
}
