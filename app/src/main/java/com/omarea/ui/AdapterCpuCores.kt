package com.omarea.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.omarea.library.shell.FreqFormatter
import com.omarea.model.CpuCoreInfo
import com.omarea.vtools.R
import java.util.*

class AdapterCpuCores(private val context: Context, private val list: ArrayList<CpuCoreInfo>?) : BaseAdapter() {

    override fun getCount(): Int {
        return list?.size ?: 0
    }

    override fun getItem(position: Int): CpuCoreInfo {
        return list!![position]
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    fun setData(list: ArrayList<CpuCoreInfo>): AdapterCpuCores {
        this.list!!.clear();
        this.list.addAll(list)
        notifyDataSetChanged()
        return this;
    }

    @SuppressLint("SetTextI18n")
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        var convertView = convertView
        if (convertView == null) {
            convertView = View.inflate(context, R.layout.list_item_cpu_core, null)
        }
        val coreInfo = getItem(position)
        val cpuChartView = convertView!!.findViewById<CpuChartBarView>(R.id.core_cpu_loading_chart)
        cpuChartView.setData(100f, 100 - coreInfo.loadRatio.toFloat() + 0.5f)
        cpuChartView.invalidate()

        val index = convertView.findViewById<TextView>(R.id.cpu_core_load)
        index.text = coreInfo.loadRatio.toInt().toString() + "%"

        val currentFreq = convertView.findViewById<TextView>(R.id.cpu_core_current_freq)
        val freqMhz = FreqFormatter.cpuKhzToMhz(coreInfo.currentFreq)
        if (freqMhz == "0") {
            currentFreq.text = "Offline"
        } else {
            currentFreq.text = freqMhz + " Mhz"
        }

        val freqRanage = convertView.findViewById<TextView>(R.id.cpu_core_freq_ranage)
        val freq = FreqFormatter.cpuKhzToMhz(coreInfo.minFreq) + " ~ " + FreqFormatter.cpuKhzToMhz(coreInfo.maxFreq) + "Mhz"
        freqRanage.text = freq
        return convertView
    }
}
