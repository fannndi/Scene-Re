@file:OptIn(DelicateCoroutinesApi::class)

package com.omarea.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import com.omarea.library.basic.AppInfoLoader
import com.omarea.model.ProcessInfo
import com.omarea.vtools.R
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class AdapterProcessMini(private val context: Context,
                         private var processes: ArrayList<ProcessInfo> = ArrayList(),
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
        val result = filterAppList()
        val groups = result.groupBy {
            if (it.name.contains(":") && isAndroidProcess(it)) {
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
        this.list = ArrayList(if (processes.size > 100) processes.subList(0, 100) else processes)
        notifyDataSetChanged()
    }

    private fun filterAppList(): ArrayList<ProcessInfo> {
        return ArrayList(processes.filter { it ->
            (
                when (filterMode) {
                    FILTER_ALL -> true
                    FILTER_ANDROID -> isAndroidProcess(it)
                    else -> true
                })
        })
    }

    private val regexPackageName = Regex(".*\\..*")

    private fun isAndroidProcess(processInfo: ProcessInfo): Boolean {
        return (processInfo.command.contains("app_process") && processInfo.name.matches(regexPackageName))
    }

    private fun loadIcon(imageView: ImageView, item: ProcessInfo) {
        if (("" + imageView.tag).equals(item.name)) {
            return
        } else {
            if (isAndroidProcess(item)) {
                GlobalScope.launch(Dispatchers.IO) {
                    var icon: Drawable? = null
                    try {
                        val name = if (item.name.contains(":")) item.name.substring(0, item.name.indexOf(":")) else item.name
                        icon = appInfoLoader.loadIcon(name).await()
                    } catch (ex: Exception) {
                    }
                    imageView.post {
                        imageView.setImageDrawable(if (icon != null) icon else androidIcon)
                        imageView.tag = item.name
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
            if (isAndroidProcess(item)) {
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
            findViewById<TextView>(R.id.ProcessFriendlyName).text = processInfo.friendlyName
            findViewById<TextView>(R.id.ProcessCPU).text = String.format("%.1f%%", processInfo.cpu)
            loadIcon(findViewById(R.id.ProcessIcon), processInfo)
        }
    }

    fun removeItem(position: Int) {
        list.removeAt(position)
        notifyDataSetChanged()
    }
}
