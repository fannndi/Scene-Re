package com.omarea.vtools.fragments

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.omarea.data.EventBus
import com.omarea.data.EventType
import com.omarea.permissions.CheckRootStatus
import com.omarea.scene_mode.CpuConfigInstaller
import com.omarea.scene_mode.ModeSwitcher
import com.omarea.store.SpfConfig
import com.omarea.utils.AccessibilityChecker
import com.omarea.utils.AccessibilityStatus
import com.omarea.utils.AccessibleServiceHelper
import com.omarea.vtools.R
import com.omarea.vtools.activities.*
import com.omarea.vtools.databinding.FragmentCpuModesBinding
import com.omarea.vtools.databinding.FragmentCpuModesContentBinding
import java.util.*
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class FragmentCpuModes : Fragment() {
    private var _binding: FragmentCpuModesBinding? = null
    private val binding get() = _binding!!
    private var contentBinding: FragmentCpuModesContentBinding? = null

    private var author: String = ""
    private var configFileInstalled: Boolean = false
    private lateinit var modeSwitcher: ModeSwitcher
    private lateinit var globalSPF: SharedPreferences
    private lateinit var themeMode: ThemeMode
    private val showServiceNotice = mutableStateOf(false)
    // 程序化更新动态响应开关时置位，避免回调里再次走校验/持久化
    private var suppressDynamicControlCallback = false
    private var cardModesView: View? = null
    private var cardServiceNoticeView: View? = null
    private var cardDynamicView: View? = null
    private var cardShortcutsView: View? = null

    companion object {
        fun createPage(themeMode: ThemeMode): Fragment {
            val fragment = FragmentCpuModes()
            fragment.themeMode = themeMode;
            return fragment
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        _binding = FragmentCpuModesBinding.inflate(inflater, container, false)
        return binding.root
    }

    /**
     * 打开系统辅助服务设置页。
     * 注意：不能在这里停掉服务 —— 用户可能只是去瞄一眼，停掉就得手动重开。
     */
    private fun openAccessibilitySettings() {
        Scene.toast(getString(R.string.accessibility_please_activate), Toast.LENGTH_SHORT)
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
        }
    }

    /**
     * 修复辅助服务：未启用则启用，已启用但没连上则强制重新绑定。
     */
    private fun repairAccessibilityService() {
        if (!CheckRootStatus.lastCheckResult) {
            Scene.toast(getString(R.string.root_required), Toast.LENGTH_SHORT)
            return
        }
        Scene.toast(getString(R.string.accessibility_repairing), Toast.LENGTH_SHORT)
        Thread {
            val ok = AccessibilityChecker.repair(context!!.applicationContext)
            Scene.toast(
                getString(if (ok) R.string.accessibility_repair_done else R.string.accessibility_please_activate),
                Toast.LENGTH_SHORT
            )
            activity?.runOnUiThread { updateState() }
        }.start()
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
        cardShortcutsView = detachFromParent(content.cpuModesCardShortcuts)

        binding.composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        binding.composeView.setContent {
            val controller = ThemeController(
                if (themeMode.isDarkMode) {
                    ColorSchemeMode.Dark
                } else {
                    ColorSchemeMode.Light
                }
            )
            MiuixTheme(controller = controller) {
                TunerScreen(
                    cardModes = cardModesView,
                    cardServiceNotice = cardServiceNoticeView,
                    showServiceNotice = showServiceNotice.value,
                    cardDynamic = cardDynamicView,
                    cardShortcuts = cardShortcutsView
                )
            }
        }

        bindMode(content.cpuConfigP0, ModeSwitcher.POWERSAVE)
        bindMode(content.cpuConfigP1, ModeSwitcher.BALANCE)
        bindMode(content.cpuConfigP2, ModeSwitcher.PERFORMANCE)
        bindMode(content.cpuConfigP3, ModeSwitcher.FAST)

        content.dynamicControlOpts2.initExpand(false)
        content.dynamicControl.setOnCheckedChangeListener { _, isChecked ->
            if (suppressDynamicControlCallback) {
                return@setOnCheckedChangeListener
            }
            if (isChecked && !modeSwitcher.modeConfigCompleted()) {
                setDynamicControlChecked(false)
                DialogHelper.alert(context!!, getString(R.string.sorry), getString(R.string.schedule_unfinished))
            } else if (isChecked && !AccessibleServiceHelper().serviceRunning(context!!)) {
                setDynamicControlChecked(false)
                openAccessibilitySettings()
            } else {
                globalSPF.edit().putBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL, isChecked).apply()
                content.dynamicControlOpts.visibility = if (isChecked) View.VISIBLE else View.GONE
                reStartService()
            }
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
            val checked = (it as CompoundButton).isChecked
            globalSPF.edit().putBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL_STRICT, checked).apply()
        }

        content.delaySwitch.isChecked = globalSPF.getBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL_DELAY, false)
        content.delaySwitch.setOnClickListener {
            val checked = (it as CompoundButton).isChecked
            globalSPF.edit().putBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL_DELAY, checked).apply()
        }

        content.firstMode.run {
            when (globalSPF.getString(SpfConfig.GLOBAL_SPF_POWERCFG_FIRST_MODE, ModeSwitcher.BALANCE)) {
                ModeSwitcher.POWERSAVE -> setSelection(0)
                ModeSwitcher.BALANCE -> setSelection(1)
                ModeSwitcher.PERFORMANCE -> setSelection(2)
                ModeSwitcher.FAST -> setSelection(3)
                ModeSwitcher.IGONED -> setSelection(4)
            }

            onItemSelectedListener = ModeOnItemSelectedListener(globalSPF) {
                reStartService()
            }
        }

        content.sleepMode.run {
            when (globalSPF.getString(SpfConfig.GLOBAL_SPF_POWERCFG_SLEEP_MODE, ModeSwitcher.POWERSAVE)) {
                ModeSwitcher.POWERSAVE -> setSelection(0)
                ModeSwitcher.BALANCE -> setSelection(1)
                ModeSwitcher.PERFORMANCE -> setSelection(2)
                ModeSwitcher.IGONED -> setSelection(3)
            }
            onItemSelectedListener = ModeOnItemSelectedListener2(globalSPF) {
            }
        }

        content.navCoreControl.setOnClickListener {
            if (!CheckRootStatus.lastCheckResult) {
                Scene.toast(getString(R.string.root_required), Toast.LENGTH_SHORT)
            } else {
                startActivity(Intent(context, ActivityCpuControl::class.java))
            }
        }
        content.navAppScene.setOnClickListener {
            if (!AccessibleServiceHelper().serviceRunning(context!!)) {
                openAccessibilitySettings()
            } else if (content.dynamicControl.isChecked) {
                val intent = Intent(context, ActivityAppConfig2::class.java)
                startActivity(intent)
            } else {
                DialogHelper.warning(
                        activity!!,
                        getString(R.string.please_notice),
                        getString(R.string.schedule_dynamic_off), {
                    val intent = Intent(context, ActivityAppConfig2::class.java)
                    startActivity(intent)
                })
            }
        }
        // 激活辅助服务按钮（未启用 -> 开设置；已启用但没连上 -> 强制重连）
        content.navSceneServiceNotActive.setOnClickListener {
            if (AccessibleServiceHelper().serviceRunning(context!!)) {
                repairAccessibilityService()
            } else {
                openAccessibilitySettings()
            }
        }

        if (!modeSwitcher.modeConfigCompleted() && configInstaller.dynamicSupport(context!!)) {
            installConfig(false)
        }
    }

    private class ModeOnItemSelectedListener(private var globalSPF: SharedPreferences, private var runnable: Runnable) : AdapterView.OnItemSelectedListener {
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

    private class ModeOnItemSelectedListener2(private var globalSPF: SharedPreferences, private var runnable: Runnable) : AdapterView.OnItemSelectedListener {
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
            val binding = contentBinding ?: return@setOnClickListener
            if (mode == ModeSwitcher.FAST && ModeSwitcher.getCurrentSource() == ModeSwitcher.SOURCE_OUTSIDE_UPERF) {
                DialogHelper.warning(
                        activity!!,
                        getString(R.string.please_notice),
                        getString(R.string.schedule_uperf_fast),
                        {
                            modeSwitcher.executePowercfgMode(mode, context!!.packageName)
                            updateState(binding.cpuConfigP3, ModeSwitcher.FAST)
                        }
                )
            } else {
                modeSwitcher.executePowercfgMode(mode, context!!.packageName)
                updateState(binding.cpuConfigP0, ModeSwitcher.POWERSAVE)
                updateState(binding.cpuConfigP1, ModeSwitcher.BALANCE)
                updateState(binding.cpuConfigP2, ModeSwitcher.PERFORMANCE)
                updateState(binding.cpuConfigP3, ModeSwitcher.FAST)
            }
        }
    }

    private fun updateState() {
        val viewBinding = contentBinding ?: return
        val outsideInstalled = configInstaller.outsideConfigInstalled()
        configFileInstalled = outsideInstalled || configInstaller.insideConfigInstalled()
        author = ModeSwitcher.getCurrentSource()

        updateState(viewBinding.cpuConfigP0, ModeSwitcher.POWERSAVE)
        updateState(viewBinding.cpuConfigP1, ModeSwitcher.BALANCE)
        updateState(viewBinding.cpuConfigP2, ModeSwitcher.PERFORMANCE)
        updateState(viewBinding.cpuConfigP3, ModeSwitcher.FAST)
        val serviceState = AccessibleServiceHelper().serviceRunning(context!!)
        val dynamicControl = globalSPF.getBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL, SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL_DEFAULT)
        setDynamicControlChecked(dynamicControl && serviceState)
        applyServiceNotice(
            if (serviceState) AccessibilityStatus.OK_UNKNOWN else AccessibilityStatus.NOT_ENABLED
        )

        if (dynamicControl && !modeSwitcher.modeConfigCompleted()) {
            globalSPF.edit().putBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL, false).apply()
            setDynamicControlChecked(false)
            reStartService()
        }

        verifyServiceBound()
    }

    /**
     * 显示/隐藏辅助服务状态条，并按状态改写文案。
     * OK / OK_UNKNOWN 不显示。
     */
    private fun applyServiceNotice(status: AccessibilityStatus) {
        val viewBinding = contentBinding ?: return
        val visible = when (status) {
            AccessibilityStatus.NOT_ENABLED, AccessibilityStatus.NOT_BOUND -> View.VISIBLE
            else -> View.GONE
        }
        if (visible == View.VISIBLE) {
            if (status == AccessibilityStatus.NOT_BOUND) {
                viewBinding.serviceNoticeTitle.setText(R.string.accessibility_not_bound)
                viewBinding.serviceNoticeDesc.setText(R.string.accessibility_not_bound_desc)
            } else {
                viewBinding.serviceNoticeTitle.setText(R.string.accessibility_activate)
                viewBinding.serviceNoticeDesc.setText(R.string.accessibility_activate_desc)
            }
        }
        showServiceNotice.value = (visible == View.VISIBLE)
        viewBinding.navSceneServiceNotActive.visibility = visible
        cardServiceNoticeView?.visibility = visible
    }

    /**
     * 后台确认服务是否真的绑定了（dumpsys 走 root shell，不能放主线程）。
     * 只在「已启用」时才有意义；结果回来后再刷新一次状态条。
     */
    private var serviceNoticeChecking = false
    private fun verifyServiceBound() {
        val ctx = context?.applicationContext ?: return
        if (!CheckRootStatus.lastCheckResult || serviceNoticeChecking) {
            return
        }
        serviceNoticeChecking = true
        Thread {
            val status = AccessibilityChecker.check(ctx, true)
            serviceNoticeChecking = false
            activity?.runOnUiThread {
                // contentBinding 为空说明视图已销毁
                if (contentBinding == null) return@runOnUiThread
                if (status == AccessibilityStatus.NOT_BOUND) {
                    applyServiceNotice(status)
                }
            }
        }.start()
    }

    // 程序化设置“动态响应”开关：不会触发校验/持久化回调，并同步选项区可见性
    private fun setDynamicControlChecked(checked: Boolean) {
        val viewBinding = contentBinding ?: return
        suppressDynamicControlCallback = true
        viewBinding.dynamicControl.isChecked = checked
        suppressDynamicControlCallback = false
        viewBinding.dynamicControlOpts.visibility = if (checked) View.VISIBLE else View.GONE
    }

    private fun updateState(button: View, mode: String) {
        val isCurrent = ModeSwitcher.getCurrentPowerMode() == mode
        button.alpha = if (configFileInstalled && isCurrent) 1f else 0.4f
    }

    override fun onResume() {
        super.onResume()

        val currentAuthor = author
        updateState()

        // 如果开启了动态响应 并且配置作者变了，重启后台服务
        val binding = contentBinding
        if (binding != null && binding.dynamicControl.isChecked && !currentAuthor.isEmpty() && currentAuthor != author) {
            reStartService()
        }
    }

    private val configInstaller = CpuConfigInstaller()

    //安装调频文件
    private fun installConfig(active: Boolean) {
        if (!configInstaller.dynamicSupport(context!!)) {
            Scene.toast(R.string.not_support_config, Toast.LENGTH_LONG)
            return
        }

        configInstaller.installOfficialConfig(context!!, "", active)
        configInstalled()
    }

    private fun configInstalled() {
        updateState()
        reStartService()
    }

    /**
     * 重启辅助服务
     */
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
        cardShortcutsView = null
    }
}

@Composable
private fun TunerScreen(
    cardModes: View?,
    cardServiceNotice: View?,
    showServiceNotice: Boolean,
    cardDynamic: View?,
    cardShortcuts: View?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        MiuixCardSection(
            cardModes,
            insideMargin = androidx.compose.foundation.layout.PaddingValues(
                start = 4.dp,
                top = 0.dp,
                end = 4.dp,
                bottom = 8.dp
            )
        )
        if (showServiceNotice) {
            MiuixCardSection(cardServiceNotice)
        }
        MiuixCardSection(
            cardDynamic,
            insideMargin = androidx.compose.foundation.layout.PaddingValues(
                start = 8.dp,
                top = 8.dp,
                end = 8.dp,
                bottom = 8.dp
            )
        )
        MiuixCardSection(
            cardShortcuts,
            insideMargin = androidx.compose.foundation.layout.PaddingValues(
                start = 8.dp,
                top = 0.dp,
                end = 8.dp,
                bottom = 8.dp
            )
        )
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun MiuixCardSection(
    view: View?,
    insideMargin: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
) {
    if (view == null) {
        return
    }
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
