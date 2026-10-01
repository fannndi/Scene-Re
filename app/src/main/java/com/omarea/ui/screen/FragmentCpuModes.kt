package com.omarea.ui.screen

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.omarea.Scene
import com.omarea.common.ui.DialogHelper
import com.omarea.common.ui.ThemeMode
import com.omarea.engine.ProfileController
import com.omarea.engine.TuningRepository
import com.omarea.data.EventBus
import com.omarea.data.EventType
import com.omarea.util.ThermalDisguise
import com.omarea.runtime.CpuConfigInstaller
import com.omarea.runtime.ModeSwitcher
import com.omarea.runtime.TrueOff
import com.omarea.data.SpfConfig
import com.omarea.util.AccessibleServiceHelper
import com.omarea.vtools.R
import com.omarea.ui.activity.ActivityAppConfig2
import com.omarea.ui.activity.ActivityBase
import com.omarea.ui.activity.ActivityCpuControl
import com.omarea.ui.activity.ActivityMiuiThermal
import com.omarea.vtools.databinding.FragmentCpuModesBinding
import com.omarea.vtools.databinding.FragmentCpuModesContentBinding
import java.util.*
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * Tuner screen: mode cards, dynamic-control options, engine ON/OFF and the
 * controls card. All apply logic lives in [ModeSwitcher] / [ProfileController];
 * this fragment only binds views.
 */
class FragmentCpuModes : Fragment() {
    private var _binding: FragmentCpuModesBinding? = null
    private val binding get() = _binding!!
    private var contentBinding: FragmentCpuModesContentBinding? = null

    private lateinit var modeSwitcher: ModeSwitcher
    private lateinit var globalSPF: SharedPreferences
    private lateinit var themeMode: ThemeMode
    private val showServiceNotice = mutableStateOf(false)
    private var cardModesView: View? = null
    private var cardServiceNoticeView: View? = null
    private var cardDynamicView: View? = null
    private var cardControlsView: View? = null

    private val configInstaller = CpuConfigInstaller()

    companion object {
        fun createPage(themeMode: ThemeMode): Fragment {
            val fragment = FragmentCpuModes()
            fragment.themeMode = themeMode
            return fragment
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        _binding = FragmentCpuModesBinding.inflate(inflater, container, false)
        return binding.root
    }

    private fun startService() {
        AccessibleServiceHelper().stopSceneModeService(activity!!.applicationContext)
        Scene.toast(getString(R.string.accessibility_please_activate), Toast.LENGTH_SHORT)
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!::themeMode.isInitialized) {
            themeMode = (activity as? ActivityBase)?.themeMode ?: ThemeMode()
        }
        globalSPF = context!!.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
        modeSwitcher = ModeSwitcher()
        contentBinding = FragmentCpuModesContentBinding.inflate(layoutInflater)
        val content = contentBinding!!
        cardModesView = detachFromParent(content.cpuModesCardModes)
        cardServiceNoticeView = detachFromParent(content.cpuModesCardServiceNotice)
        cardDynamicView = detachFromParent(content.cpuModesCardDynamic)
        cardControlsView = detachFromParent(content.cpuModesCardControls)

        // Sync profile state (engine init, Parameter.sh catalog) off the main thread.
        Thread { modeSwitcher.initPowerCfg() }.start()

        binding.composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        binding.composeView.setContent {
            val controller = ThemeController(
                if (themeMode.isDarkMode) ColorSchemeMode.Dark else ColorSchemeMode.Light
            )
            MiuixTheme(controller = controller) {
                TunerScreen(
                    cardModes = cardModesView,
                    cardServiceNotice = cardServiceNoticeView,
                    showServiceNotice = showServiceNotice.value,
                    cardDynamic = cardDynamicView,
                    cardControls = cardControlsView
                )
            }
        }

        bindMode(content.cpuConfigP0, ModeSwitcher.POWERSAVE)
        bindMode(content.cpuConfigP1, ModeSwitcher.BALANCE)
        bindMode(content.cpuConfigP2, ModeSwitcher.PERFORMANCE)
        bindMode(content.cpuConfigP3, ModeSwitcher.FAST)

        bindDynamicControl(content)
        bindModePickers(content)
        bindSourceRow(content)
        bindEngineSwitch(content)
        bindControlsCard(content)

        // 卓越性能 目前仅限888处理器开放
        content.extremePerformance.visibility = if (ThermalDisguise().supported()) View.VISIBLE else View.GONE
        content.extremePerformanceOn.setOnClickListener {
            if ((it as CompoundButton).isChecked) {
                ThermalDisguise().disableMessage()
            } else {
                ThermalDisguise().resumeMessage()
            }
        }
    }

    private fun bindDynamicControl(content: FragmentCpuModesContentBinding) {
        content.dynamicControl.setOnClickListener {
            val value = (it as Switch).isChecked
            if (value && !modeSwitcher.modeConfigCompleted()) {
                it.isChecked = false
                DialogHelper.alert(context!!, getString(R.string.sorry), getString(R.string.schedule_unfinished))
            } else if (value && !AccessibleServiceHelper().serviceRunning(context!!)) {
                it.isChecked = false
                startService()
            } else {
                globalSPF.edit().putBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL, value).apply()
                reStartService()
            }
        }
        content.dynamicControlOpts2.initExpand(false)
        content.dynamicControl.setOnCheckedChangeListener { _, isChecked ->
            content.dynamicControlOpts.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        content.dynamicControlToggle.setOnClickListener {
            content.dynamicControlOpts2.toggleExpand()
            if (content.dynamicControlOpts2.isExpand) {
                (it as ImageView).setImageDrawable(ContextCompat.getDrawable(context!!, R.drawable.arrow_up))
            } else {
                (it as ImageView).setImageDrawable(ContextCompat.getDrawable(context!!, R.drawable.arrow_down))
            }
        }

        content.strictMode.isChecked = globalSPF.getBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL_STRICT, false)
        content.strictMode.setOnClickListener {
            globalSPF.edit()
                .putBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL_STRICT, (it as CompoundButton).isChecked)
                .apply()
        }

        content.delaySwitch.isChecked = globalSPF.getBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL_DELAY, false)
        content.delaySwitch.setOnClickListener {
            globalSPF.edit()
                .putBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL_DELAY, (it as CompoundButton).isChecked)
                .apply()
        }
    }

    private fun bindModePickers(content: FragmentCpuModesContentBinding) {
        content.firstMode.run {
            when (globalSPF.getString(SpfConfig.GLOBAL_SPF_POWERCFG_FIRST_MODE, ModeSwitcher.BALANCE)) {
                ModeSwitcher.POWERSAVE -> setSelection(0)
                ModeSwitcher.BALANCE -> setSelection(1)
                ModeSwitcher.PERFORMANCE -> setSelection(2)
                ModeSwitcher.FAST -> setSelection(3)
                ModeSwitcher.IGONED -> setSelection(4)
            }
            onItemSelectedListener = ModeOnItemSelectedListener(globalSPF) { reStartService() }
        }

        content.sleepMode.run {
            when (globalSPF.getString(SpfConfig.GLOBAL_SPF_POWERCFG_SLEEP_MODE, ModeSwitcher.POWERSAVE)) {
                ModeSwitcher.POWERSAVE -> setSelection(0)
                ModeSwitcher.BALANCE -> setSelection(1)
                ModeSwitcher.PERFORMANCE -> setSelection(2)
                ModeSwitcher.IGONED -> setSelection(3)
            }
            onItemSelectedListener = ModeOnItemSelectedListener2(globalSPF) { reStartService() }
        }
    }

    /**
     * Config author row: with an external /data/powercfg.sh installed it
     * offers removal; otherwise it explains the tuning-JSON engine.
     */
    private fun bindSourceRow(content: FragmentCpuModesContentBinding) {
        val sourceClick = View.OnClickListener {
            if (configInstaller.outsideConfigInstalled()) {
                DialogHelper.warning(
                    activity!!,
                    getString(R.string.make_choice),
                    getString(R.string.schedule_remove_outside)
                ) {
                    configInstaller.removeOutsideConfig()
                    reStartService()
                    updateState()
                }
            } else {
                DialogHelper.helpInfo(
                    activity!!,
                    getString(R.string.tuning_source_title),
                    getString(R.string.tuning_source_help, TuningRepository.dir().absolutePath)
                )
            }
        }
        content.configAuthorIcon.setOnClickListener(sourceClick)
        content.configAuthor.setOnClickListener(sourceClick)
    }

    private fun bindEngineSwitch(content: FragmentCpuModesContentBinding) {
        val profileOff = globalSPF.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_OFF, false)
        content.profileEngineSwitch.isChecked = !profileOff
        content.profileEngineSwitch.setOnCheckedChangeListener { view, checked ->
            if (!TrueOff.guardOrToast(requireContext())) {
                view.isChecked = !checked
                return@setOnCheckedChangeListener
            }
            globalSPF.edit().putBoolean(SpfConfig.GLOBAL_SPF_PROFILE_OFF, !checked).apply()
            // OFF: stock release profile + MIUI daemons + default props.
            // ON : MIUI daemons stopped right away; profile applies on next switch.
            ProfileController.setEngineEnabled(requireContext(), checked)
            ModeSwitcher().clearInitedState()
        }
        content.kernelProfileFolder.setOnClickListener {
            TuningRepository.openFolder(requireContext())
        }
    }

    private fun bindControlsCard(content: FragmentCpuModesContentBinding) {
        content.navSceneServiceNotActive.setOnClickListener {
            startService()
        }
        content.navAppProfiles.setOnClickListener {
            if (!AccessibleServiceHelper().serviceRunning(context!!)) {
                startService()
            } else if (content.dynamicControl.isChecked) {
                startActivity(Intent(context, ActivityAppConfig2::class.java))
            } else {
                DialogHelper.warning(
                    activity!!,
                    getString(R.string.please_notice),
                    getString(R.string.schedule_dynamic_off)
                ) {
                    startActivity(Intent(context, ActivityAppConfig2::class.java))
                }
            }
        }
        content.navCpuControl.setOnClickListener {
            // Read-only: view live CPU state. Editing happens from the profile
            // cards while the profile engine is OFF.
            startActivity(
                Intent(context, ActivityCpuControl::class.java).putExtra("readonly", true)
            )
        }
        if (Build.MANUFACTURER.lowercase(Locale.getDefault()) == "xiaomi") {
            content.navMiuiThermal.setOnClickListener {
                startActivity(Intent(context, ActivityMiuiThermal::class.java))
            }
        } else {
            content.navMiuiThermal.visibility = View.GONE
        }
        content.navFreeze.setOnClickListener {
            if (AccessibleServiceHelper().serviceRunning(context!!)) {
                val intent = Intent(Intent.ACTION_VIEW)
                intent.setClassName("com.omarea.vtools", "com.omarea.ui.activity.ActivityFreezeApps2")
                startActivity(intent)
            } else {
                startService()
            }
        }
    }

    private class ModeOnItemSelectedListener(
        private var globalSPF: SharedPreferences,
        private var runnable: Runnable
    ) : AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: AdapterView<*>?) {
        }

        @SuppressLint("ApplySharedPref")
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            var mode = ModeSwitcher.DEFAULT
            when (position) {
                0 -> mode = ModeSwitcher.POWERSAVE
                1 -> mode = ModeSwitcher.BALANCE
                2 -> mode = ModeSwitcher.PERFORMANCE
                3 -> mode = ModeSwitcher.FAST
                4 -> mode = ModeSwitcher.IGONED
            }
            if (globalSPF.getString(SpfConfig.GLOBAL_SPF_POWERCFG_FIRST_MODE, ModeSwitcher.DEFAULT) != mode) {
                globalSPF.edit().putString(SpfConfig.GLOBAL_SPF_POWERCFG_FIRST_MODE, mode).commit()
                runnable.run()
            }
        }
    }

    private class ModeOnItemSelectedListener2(
        private var globalSPF: SharedPreferences,
        private var runnable: Runnable
    ) : AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: AdapterView<*>?) {
        }

        @SuppressLint("ApplySharedPref")
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            var mode = ModeSwitcher.POWERSAVE
            when (position) {
                0 -> mode = ModeSwitcher.POWERSAVE
                1 -> mode = ModeSwitcher.BALANCE
                2 -> mode = ModeSwitcher.PERFORMANCE
                3 -> mode = ModeSwitcher.IGONED
            }
            if (globalSPF.getString(SpfConfig.GLOBAL_SPF_POWERCFG_SLEEP_MODE, ModeSwitcher.POWERSAVE) != mode) {
                globalSPF.edit().putString(SpfConfig.GLOBAL_SPF_POWERCFG_SLEEP_MODE, mode).commit()
                runnable.run()
            }
        }
    }

    private fun bindMode(button: View, mode: String) {
        button.setOnClickListener {
            if (globalSPF.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_OFF, false)) {
                // While the engine is OFF the cards open the live editor instead.
                startActivity(Intent(context, ActivityCpuControl::class.java).putExtra("profile", mode))
                return@setOnClickListener
            }
            if (!TrueOff.guardOrToast(requireContext())) return@setOnClickListener
            modeSwitcher.executePowercfgMode(mode, context!!.packageName)
            val binding = contentBinding ?: return@setOnClickListener
            updateState(binding.cpuConfigP0, ModeSwitcher.POWERSAVE)
            updateState(binding.cpuConfigP1, ModeSwitcher.BALANCE)
            updateState(binding.cpuConfigP2, ModeSwitcher.PERFORMANCE)
            updateState(binding.cpuConfigP3, ModeSwitcher.FAST)
        }
    }

    private fun updateState() {
        val viewBinding = contentBinding ?: return
        viewBinding.configAuthor.text = ModeSwitcher.getCurrentSourceName()

        updateState(viewBinding.cpuConfigP0, ModeSwitcher.POWERSAVE)
        updateState(viewBinding.cpuConfigP1, ModeSwitcher.BALANCE)
        updateState(viewBinding.cpuConfigP2, ModeSwitcher.PERFORMANCE)
        updateState(viewBinding.cpuConfigP3, ModeSwitcher.FAST)

        val serviceState = AccessibleServiceHelper().serviceRunning(context!!)
        val dynamicControl = globalSPF.getBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL, SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL_DEFAULT)
        viewBinding.dynamicControl.isChecked = dynamicControl && serviceState
        val serviceNoticeVisible = if (serviceState) View.GONE else View.VISIBLE
        showServiceNotice.value = serviceNoticeVisible == View.VISIBLE
        viewBinding.navSceneServiceNotActive.visibility = serviceNoticeVisible
        cardServiceNoticeView?.visibility = serviceNoticeVisible

        if (dynamicControl && !modeSwitcher.modeConfigCompleted()) {
            globalSPF.edit().putBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL, false).apply()
            viewBinding.dynamicControl.isChecked = false
            reStartService()
        }
        viewBinding.dynamicControlOpts.postDelayed({
            val postBinding = contentBinding ?: return@postDelayed
            postBinding.dynamicControlOpts.visibility = if (postBinding.dynamicControl.isChecked) View.VISIBLE else View.GONE
        }, 15)
        viewBinding.extremePerformanceOn.isChecked = ThermalDisguise().isDisabled()
    }

    private fun updateState(button: View, mode: String) {
        val isCurrent = ModeSwitcher.getCurrentPowerMode() == mode
        button.alpha = if (isCurrent) 1f else 0.4f
    }

    override fun onResume() {
        super.onResume()
        updateState()
    }

    private fun reStartService() {
        EventBus.publish(EventType.SERVICE_UPDATE)
    }

    private fun detachFromParent(view: View): View {
        (view.parent as? ViewGroup)?.removeView(view)
        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        contentBinding = null
        cardModesView = null
        cardServiceNoticeView = null
        cardDynamicView = null
        cardControlsView = null
    }
}

@Composable
private fun TunerScreen(
    cardModes: View?,
    cardServiceNotice: View?,
    showServiceNotice: Boolean,
    cardDynamic: View?,
    cardControls: View?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MiuixCardSection(
            cardModes,
            insideMargin = androidx.compose.foundation.layout.PaddingValues(
                start = 4.dp, top = 0.dp, end = 4.dp, bottom = 8.dp
            )
        )
        if (showServiceNotice) {
            MiuixCardSection(cardServiceNotice)
        }
        MiuixCardSection(
            cardDynamic,
            insideMargin = androidx.compose.foundation.layout.PaddingValues(
                start = 8.dp, top = 8.dp, end = 8.dp, bottom = 8.dp
            )
        )
        MiuixCardSection(
            cardControls,
            insideMargin = androidx.compose.foundation.layout.PaddingValues(
                start = 8.dp, top = 0.dp, end = 8.dp, bottom = 8.dp
            )
        )
    }
}

@Composable
private fun MiuixCardSection(
    view: View?,
    insideMargin: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
) {
    if (view == null) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        insideMargin = insideMargin,
        colors = CardDefaults.defaultColors()
    ) {
        AndroidView(
            factory = {
                view.apply {
                    layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                }
            }
        )
    }
}
