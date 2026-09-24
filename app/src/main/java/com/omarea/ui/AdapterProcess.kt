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

class AdapterProcess(private val context: Context,
                     private var processes: ArrayList<ProcessInfo> = ArrayList(),
                     private var keywords: String = "",
                     private var sortMode: Int = SORT_MODE_CPU,
                     private var filterMode: Int = FILTER_ANDROID_USER) : BaseAdapter() {
    private val appInfoLoader = AppInfoLoader(context, 100)
    private val androidIcon = context.getDrawable(R.drawable.process_android)
    private val linuxIcon = context.getDrawable(R.drawable.process_linux)

    companion object {
        val SORT_MODE_DEFAULT = 1;
        val SORT_MODE_CPU = 4;
        val SORT_MODE_RES = 8;
        val SORT_MODE_PID = 16;

        val FILTER_ALL = 1;
        val FILTER_OTHER = 4;
        val FILTER_ANDROID_USER = 8;
        val FILTER_ANDROID_SYSTEM = 16;
        val FILTER_ANDROID = 32;
    }

    private val pm = context.packageManager

    /**
     * Scope owned by this adapter (replaces GlobalScope). The activity polls the
     * process list on a timer and every getView launched an unbounded icon-load
     * job, so jobs piled up as rows were recycled. Cancel via [destroy].
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Cancels pending icon loads. Call from the owning view's onDestroy. */
    fun destroy() {
        scope.cancel()
    }

    private lateinit var list: ArrayList<ProcessInfo>
    private val nameCache = context.getSharedPreferences("ProcessNameCache", Context.MODE_PRIVATE)

    init {
        setList()
        if (processes.size > 0) {
            loadLabel()
        }
    }

    override fun getCount(): Int {
        return list.size ?: 0
    }

    override fun getItem(position: Int): ProcessInfo {
        return list[position]
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    private fun setList() {
        this.list = filterAppList()
        notifyDataSetChanged()
    }

    private fun keywordSearch(item: ProcessInfo, text: String): Boolean {
        return item.friendlyName.toString().lowercase().contains(text) || item.name.toString().lowercase().contains(text) || item.user.toString().lowercase().contains(text) || item.command.toString().lowercase().contains(text) || item.cmdline.toString().lowercase().contains(text)
    }

    private fun filterAppList(): ArrayList<ProcessInfo> {
        val text = keywords.lowercase()
        val keywordsEmpty = text.isEmpty()
        return ArrayList(processes.filter { it ->
            (keywordsEmpty || keywordSearch(it, text)) && (
                    when (filterMode) {
                        FILTER_ALL -> true
                        FILTER_ANDROID_USER -> ProcessFilter.isUserProcess(it)
                        FILTER_ANDROID_SYSTEM -> ProcessFilter.isSystemProcess(it)
                        FILTER_ANDROID -> ProcessFilter.isAndroidProcess(it)
                        FILTER_OTHER -> ProcessFilter.isOtherProcess(it)
                        else -> true
                    })
        }.sortedBy {
            when (sortMode) {
                SORT_MODE_DEFAULT -> it.pid
                SORT_MODE_CPU -> -(it.getCpu() * 10).toInt()
                SORT_MODE_RES -> -(it.res * 100).toInt()
                SORT_MODE_PID -> -it.pid
                else -> it.pid
            }
        })
    }



    private fun loadIcon(imageView: ImageView, item: ProcessInfo) {
        if (("" + imageView.tag).equals(item.name)) {
            return
        } else {
            if (ProcessFilter.isAndroidProcess(item)) {
                val target = imageView
                scope.launch {
                    var icon: Drawable? = null
                    try {
                        val name = if (item.name.contains(":")) item.name.substring(0, item.name.indexOf(":")) else item.name
                        icon = appInfoLoader.loadIcon(name).await()
                    } catch (ex: Exception) {
                    }
                    // Re-check the tag: this row may have been rebound meanwhile.
                    if (("" + target.tag) != item.name) {
                        target.setImageDrawable(if (icon != null) icon else androidIcon)
                        target.tag = item.name
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
            convertView = View.inflate(context, R.layout.list_item_process_item, null)
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
        loadLabel()
        setList()
    }

    private fun updateRow(position: Int, view: View) {
        val processInfo = getItem(position);
        view.run {
            if (processInfo.friendlyName == processInfo.name) {
                findViewById<TextView>(R.id.ProcessFriendlyName).text = SearchHighlighter.highlight(processInfo.friendlyName, keywords)
                findViewById<TextView>(R.id.ProcessName).run {
                    visibility = View.GONE
                    text = ""
                }
            } else {
                findViewById<TextView>(R.id.ProcessFriendlyName).text = SearchHighlighter.highlight(processInfo.friendlyName, keywords)
                findViewById<TextView>(R.id.ProcessName).run {
                    visibility = View.VISIBLE
                    text = SearchHighlighter.highlight(processInfo.name, keywords)
                }
            }
            findViewById<TextView>(R.id.ProcessPID).text = processInfo.pid.toString()
            findViewById<TextView>(R.id.ProcessCPU).text = "" + processInfo.getCpu() + "%"
            if (processInfo.res > 8192) {
                findViewById<TextView>(R.id.ProcessRES).text = "" + (processInfo.res / 1024).toInt() + "MB"
            } else {
                findViewById<TextView>(R.id.ProcessRES).text = "" + processInfo.res + "KB"
            }
            loadIcon(findViewById<ImageView>(R.id.ProcessIcon), processInfo)
        }
    }

    fun removeItem(position: Int) {
        if (position in list.indices) {
            list.removeAt(position)
        }
        notifyDataSetChanged()
    }
}
