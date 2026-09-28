package com.omarea.vtools.activities

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.CompoundButton
import com.omarea.common.ui.AdapterAppChooser
import com.omarea.common.ui.DialogAppChooser
import com.omarea.common.ui.ProgressBarDialog
import com.omarea.data.EventBus
import com.omarea.data.EventType
import com.omarea.store.SpfConfig
import com.omarea.utils.AppListHelper
import com.omarea.vtools.R
import com.omarea.vtools.databinding.ActivityAutoClickBinding


class ActivityAutoClick : ActivityBase() {
    private lateinit var processBarDialog: ProgressBarDialog
    private lateinit var globalSPF: SharedPreferences
    internal val myHandler: Handler = Handler(Looper.getMainLooper())
    private lateinit var binding: ActivityAutoClickBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAutoClickBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setBackArrow()
        processBarDialog = ProgressBarDialog(this)

        globalSPF = getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)

        bindSPF(binding.settingsAutoInstall, globalSPF, SpfConfig.GLOBAL_SPF_AUTO_INSTALL, false)
    }

    override fun onResume() {
        super.onResume()
        title = getString(R.string.menu_auto_click)
    }

    private fun bindSPF(checkBox: CompoundButton, spf: SharedPreferences, prop: String, defValue: Boolean = false) {
        checkBox.isChecked = spf.getBoolean(prop, defValue)
        checkBox.setOnClickListener { view ->
            spf.edit().putBoolean(prop, (view as CompoundButton).isChecked).apply()
            EventBus.publish(EventType.SERVICE_UPDATE)
        }
    }
}
