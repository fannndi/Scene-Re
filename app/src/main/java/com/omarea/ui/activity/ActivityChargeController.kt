package com.omarea.ui.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.ui.DialogHelper
import com.omarea.data.GlobalStatus
import com.omarea.util.BatteryCapacity
import com.omarea.util.BatteryUtils
import com.omarea.vtools.R
import com.omarea.vtools.databinding.ActivityChargeControllerBinding
import java.util.*

/**
 * Battery / charging monitor (READ-ONLY).
 *
 * Scene never modifies charge parameters (policy: the ROM/kernel owns
 * charging — see docs/ARCHITECTURE.md). This screen only displays what the
 * kernel reports: capacity/temperature/voltage, uevent dumps, the kernel's
 * own current limit, PD/step-charge state and the (read-only) capacity
 * values. Former controls — battery protection, QC limit, night charge,
 * PD toggle, step toggle, charge_full/capacity setters — were removed.
 *
 * Responsibility: read-only display + battery-history shortcuts.
 * Non-goals: any charge control, charge sampling (ChargeCurve).
 */
class ActivityChargeController : ActivityBase() {
    private lateinit var binding: ActivityChargeControllerBinding

    private var qcSettingSuupport = false
    private var pdSettingSupport = false
    private var stepChargeSupport = false

    private var myHandler: Handler = Handler(Looper.getMainLooper())
    private var timer: Timer? = null
    private var batteryUtils = BatteryUtils()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChargeControllerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setBackArrow()
        BatteryHub.bind(
            this,
            BatteryHub.Tab.HARDWARE,
            true,
            binding.hubTabs.hubTabLive,
            binding.hubTabs.hubTabApps,
            binding.hubTabs.hubTabHardware
        )

        onViewCreated()
    }

    private fun onViewCreated() {
        qcSettingSuupport = batteryUtils.qcSettingSupport()
        pdSettingSupport = batteryUtils.pdSupported()
        stepChargeSupport = batteryUtils.stepChargeSupport()

        // Read-only rows only appear for nodes the kernel exposes.
        binding.settingsQcPanel.visibility = if (qcSettingSuupport) View.VISIBLE else View.GONE
        binding.settingsPdSupport.visibility = if (pdSettingSupport) View.VISIBLE else View.GONE
        binding.settingsStepCharge.visibility = if (stepChargeSupport) View.VISIBLE else View.GONE
        binding.chargeReadOnlyNote.text = getString(R.string.charge_read_only_note)

        binding.btnBatteryHistory.setOnClickListener {
            try {
                val powerUsageIntent = Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
                val resolveInfo = packageManager.resolveActivity(powerUsageIntent, 0)
                if (resolveInfo != null) {
                    startActivity(powerUsageIntent)
                }
            } catch (ex: Exception) {
            }
        }
        binding.btnBatteryHistoryDel.setOnClickListener {
            DialogHelper.confirm(
                this,
                "Reboot required",
                "Deleting battery usage records requires an immediate reboot. Continue?",
                {
                    KeepShellPublic.doCmdSync(
                        "rm -f /data/system/batterystats-checkin.bin;" +
                                "rm -f /data/system/batterystats-daily.xml;" +
                                "rm -f /data/system/batterystats.bin;" +
                                "rm -rf /data/system/battery-history;" +
                                "rm -rf /data/charge_logger;" +
                                "rm -rf /data/vendor/charge_logger;" +
                                "sync;" +
                                "sleep 2;" +
                                "reboot;"
                    )
                })
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onResume() {
        super.onResume()

        title = getString(R.string.menu_battery)

        val battryStatus = findViewById<TextView>(R.id.battrystatus)

        timer = Timer()
        timer!!.schedule(object : TimerTask() {
            override fun run() {
                var limit = ""
                var pdAllowed = false
                var pdActive = false
                var stepEnabled = false
                if (qcSettingSuupport) {
                    limit = batteryUtils.getQcLimit()
                }
                if (pdSettingSupport) {
                    pdAllowed = batteryUtils.pdAllowed()
                    pdActive = batteryUtils.pdActive()
                }
                if (stepChargeSupport) {
                    stepEnabled = batteryUtils.getStepCharge()
                }
                val batteryInfo = batteryUtils.batteryInfo
                val usbInfo = batteryUtils.usbInfo
                val level = GlobalStatus.batteryCapacity
                val temp = GlobalStatus.updateBatteryTemperature()
                val kernelCapacity = batteryUtils.getKernelCapacity(level)
                val batteryMAH = BatteryCapacity().getBatteryCapacity(context).toInt().toString() + "mAh" + "   "
                val voltage = GlobalStatus.batteryVoltage
                val capacityNow = batteryUtils.getCapacity()
                val chargeFullNow = batteryUtils.getChargeFull()

                myHandler.post {
                    try {
                        if (qcSettingSuupport) {
                            binding.settingsQcLimitCurrent.text =
                                getString(R.string.battery_reality_limit) + limit
                        }
                        if (pdSettingSupport) {
                            binding.settingsPdAllowedState.text = getString(
                                R.string.battery_pd_allowed_fmt,
                                getString(if (pdAllowed) R.string.state_on else R.string.state_off)
                            )
                            binding.settingsPdState.text =
                                if (pdActive) getString(R.string.battery_pd_active_1) else getString(R.string.battery_pd_active_0)
                        }
                        if (stepChargeSupport) {
                            binding.settingsStepChargeState.text = getString(
                                R.string.battery_step_charge_state_fmt,
                                getString(if (stepEnabled) R.string.state_on else R.string.state_off)
                            )
                        }

                        battryStatus.text = getString(R.string.battery_title) +
                                batteryMAH +
                                temp + "°C   " +
                                voltage + "v"
                        if (kernelCapacity > -1) {
                            val str = "" + kernelCapacity + "%"
                            val ss = SpannableString(str)
                            if (str.contains(".")) {
                                val small = AbsoluteSizeSpan((binding.battrystatusLevel.textSize * 0.3).toInt(), false)
                                ss.setSpan(small, str.indexOf("."), str.lastIndexOf("%"), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                val medium = AbsoluteSizeSpan((binding.battrystatusLevel.textSize * 0.5).toInt(), false)
                                ss.setSpan(medium, str.indexOf("%"), str.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                            binding.battrystatusLevel.text = ss
                        } else {
                            binding.battrystatusLevel.text = "" + level + "%"
                        }

                        binding.batteryCapacityChart.setData(100f, 100f - level, temp.toFloat())
                        binding.batteryUevent.text = batteryInfo
                        binding.batteryUsbUevent.text = usbInfo

                        // Read-only capacity values (no setters: charging is
                        // read-only by policy).
                        binding.batteryForgeryRatio.text =
                            if (capacityNow > 0) "$capacityNow%" else "--%"
                        binding.batteryForgeryFullNow.text =
                            if (chargeFullNow > 0) chargeFullNow.toString() + "mAh" else "--mAh"
                        binding.batteryForgery.visibility =
                            if (capacityNow > 0 || chargeFullNow > 0) View.VISIBLE else View.GONE
                    } catch (ex: java.lang.Exception) {
                    }
                }
            }
        }, 0, 3000)
    }

    override fun onPause() {
        super.onPause()
        if (timer != null) {
            timer!!.cancel()
            timer = null
        }
    }
}
