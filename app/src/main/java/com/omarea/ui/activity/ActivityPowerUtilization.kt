@file:OptIn(DelicateCoroutinesApi::class)

package com.omarea.ui.activity

import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.omarea.data.GlobalStatus
import com.omarea.util.BatteryCapacity
import com.omarea.util.BatteryUtils
import com.omarea.data.BatteryHistoryStore
import com.omarea.ui.power.AdapterBatteryStats
import com.omarea.vtools.R
import com.omarea.vtools.databinding.ActivityPowerUtilizationBinding
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.*

class ActivityPowerUtilization : ActivityBase() {
    private lateinit var storage: BatteryHistoryStore
    private lateinit var binding: ActivityPowerUtilizationBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPowerUtilizationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setBackArrow()
        storage = BatteryHistoryStore(context)
        binding.batteryStatsEmpty.emptyText.text = getString(R.string.empty_state_power_util)

        GlobalScope.launch(Dispatchers.Main) {
            BatteryHub.bind(
                this@ActivityPowerUtilization,
                BatteryHub.Tab.APPS,
                BatteryUtils().qcSettingSupport() || batteryUtils.bpSettingSupport(),
                binding.hubTabs.hubTabApps,
                binding.hubTabs.hubTabHardware
            )
        }
        binding.batteryStats.layoutManager = LinearLayoutManager(this).apply {
            orientation = LinearLayoutManager.VERTICAL
            isSmoothScrollbarEnabled = false
        }
    }

    override fun onResume() {
        super.onResume()
        title = getString(R.string.menu_power_utilization)
        updateUI()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.delete, menu)
        return true
    }

    //右上角菜单
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_delete -> {
                BatteryHistoryStore(context).clearData()
                Toast.makeText(context, "Stats cleared", Toast.LENGTH_SHORT).show()
                updateUI()
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private var batteryUtils = BatteryUtils()
    private val handler = Handler(Looper.getMainLooper())
    private fun updateUI() {
        val level = GlobalStatus.batteryCapacity
        val temp = GlobalStatus.updateBatteryTemperature()
        val kernelCapacity = batteryUtils.getKernelCapacity(level)
        val batteryMAH = BatteryCapacity().getBatteryCapacity(this).toInt().toString() + "mAh" + "   "
        val voltage = GlobalStatus.batteryVoltage

        val data = storage.getAvgData()
        val rows = data.filter {
            // 仅显示运行时间超过2分钟的应用数据，避免误差过大（totalMs = 真实采样时长）
            it.totalMs > 120_000
        }

        handler.post {
            binding.batteryStats.adapter = AdapterBatteryStats(context, rows)
            // Never a silently blank list: explain that samples are pending.
            binding.batteryStatsEmpty.root.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            binding.batteryStats.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE

            binding.viewTime.invalidate()

            if (kernelCapacity > -1) {
                val str = "$kernelCapacity%"
                val ss = SpannableString(str)
                if (str.contains(".")) {
                    val small = AbsoluteSizeSpan((binding.batteryCapacity.textSize * 0.45).toInt(), false)
                    ss.setSpan(small, str.indexOf("."), str.lastIndexOf("%"), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    val medium = AbsoluteSizeSpan((binding.batteryCapacity.textSize * 0.65).toInt(), false)
                    ss.setSpan(medium, str.indexOf("%"), str.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                binding.batteryCapacity.text = ss
            } else {
                binding.batteryCapacity.text = "" + level + "%"
            }

            binding.batteryStatus.text = (when (GlobalStatus.batteryStatus) {
                BatteryManager.BATTERY_STATUS_DISCHARGING -> {
                    getString(R.string.battery_status_discharging)
                }
                BatteryManager.BATTERY_STATUS_CHARGING -> {
                    getString(R.string.battery_status_charging)
                }
                BatteryManager.BATTERY_STATUS_FULL -> {
                    getString(R.string.battery_status_full)
                }
                BatteryManager.BATTERY_STATUS_UNKNOWN -> {
                    getString(R.string.battery_status_unknown)
                }
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> {
                    getString(R.string.battery_status_not_charging)
                }
                else -> getString(R.string.battery_status_unknown)
            })
            binding.batteryVoltage.text = "${voltage}v"
            binding.batteryTemperature.text =  "$temp°C"
            binding.batterySize.text = batteryMAH
        }

    }

}

