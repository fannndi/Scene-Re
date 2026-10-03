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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.omarea.engine.DeviceCaps
import com.omarea.engine.ProfileController
import com.omarea.engine.ProfileKey
import com.omarea.engine.ProfileStore
import com.omarea.engine.TuningRepository
import com.omarea.ui.theme.SceneDimens
import com.omarea.data.EventBus
import com.omarea.data.EventType
import com.omarea.util.ThermalDisguise
import com.omarea.util.CheckRootStatus
import com.omarea.util.RootState
import com.omarea.runtime.CpuConfigInstaller
import com.omarea.runtime.ModeSwitcher
import com.omarea.runtime.NoRootMode
import com.omarea.runtime.TrueOff
import com.omarea.data.SpfConfig
import com.omarea.util.AccessibleServiceHelper
import com.omarea.vtools.R
import com.omarea.ui.activity.ActivityAppConfig2
import com.omarea.ui.activity.ActivityBase
import com.omarea.ui.activity.ActivityCpuControl
import com.omarea.ui.activity.ActivityDiagnostics
import com.omarea.ui.activity.ActivityMain
import com.omarea.ui.activity.ActivityMiuiThermal
import com.omarea.vtools.databinding.FragmentCpuModesBinding
import com.omarea.vtools.databinding.FragmentCpuModesContentBinding
import java.util.*
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.omarea.ui.theme.SceneTheme

/**
 * Tuner screen: the Profile card (master switch, TRUE OFF, profile rows),
 * dynamic-control options and the controls card.
 *
 * Tapping a profile applies it while the engine is ON; while the engine is OFF
 * it opens the profile editor (config editing only — nothing is written to the
 * kernel, see [ActivityCpuControl]).
 *
 * Responsibility: binding views to [ModeSwitcher] / [ProfileStore] state.
 * Non-goals: planning/applying tuning (engine package).
 */
class FragmentCpuModes : Fragment() {
    private var _binding: FragmentCpuModesBinding? = null
    private val binding get() = _binding!!
    private var contentBinding: FragmentCpuModesContentBinding? = null

    private lateinit var modeSwitcher: ModeSwitcher
    private lateinit var globalSPF: SharedPreferences
    private lateinit var themeMode: ThemeMode
    private val showServiceNotice = mutableStateOf(false)
    private val profileCardState = mutableStateOf(TunerProfileCardState())
    /** Mode whose apply is in flight (row reads "Applying…" and stops taps). */
    private val applyingMode = mutableStateOf<String?>(null)
    /** Last apply outcome, rendered under the profile rows. */
    private val applyResult = mutableStateOf("")
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

        private fun modeTitleRes(mode: String): Int = when (mode) {
            ProfileKey.POWERSAVE -> R.string.powersave
            ProfileKey.BALANCE -> R.string.balance
            ProfileKey.PERFORMANCE -> R.string.performance
            else -> R.string.fast
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
        cardServiceNoticeView = detachFromParent(content.cpuModesCardServiceNotice)
        cardDynamicView = detachFromParent(content.cpuModesCardDynamic)
        cardControlsView = detachFromParent(content.cpuModesCardControls)

        // Sync profile state off the main thread: init + restore the saved
        // mode. ensureReady() is idempotent per process and never clears the
        // active mode (this call used to race UI taps and reset the mode).
        Thread {
            modeSwitcher.ensureReady()
            ProfileController.syncCatalog(requireContext())
            _binding?.root?.post {
                updateState()
                refreshProfileCardState()
            }
        }.start()

        binding.composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        binding.composeView.setContent {
            val controller = SceneTheme.controller(themeMode.isDarkMode)
            // Monitor mode: the engine switch is locked; a note explains why.
            val rootState by CheckRootStatus.rootState.collectAsState()
            val rootMissing = rootState == RootState.MISSING || rootState == RootState.DENIED
            val cardState = if (rootMissing) profileCardState.value.copy(
                engineEnabled = false,
                engineNote = getString(R.string.profile_engine_no_root)
            ) else profileCardState.value
            MiuixTheme(controller = controller) {
                TunerScreen(
                    profileState = cardState,
                    applyingMode = applyingMode.value,
                    applyResult = applyResult.value,
                    onEngineToggle = { toggleEngine(it) },
                    onTrueOffToggle = { toggleTrueOff(it) },
                    onProfileClick = { onProfileClick(it) },
                    onProfileEdit = { onProfileEdit(it) },
                    onSourceClick = { showSourceDialog() },
                    cardServiceNotice = cardServiceNoticeView,
                    showServiceNotice = showServiceNotice.value,
                    cardDynamic = cardDynamicView,
                    cardControls = cardControlsView
                )
            }
        }

        bindDynamicControl(content)
        bindModePickers(content)
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

    // ------------------------------------------------------------ profile card
    /** Rebuilds the Profile card state (JSON reads happen off the main thread). */
    private fun refreshProfileCardState() {
        val ctx = context ?: return
        // The top-bar chip mirrors this card's state (engine / TRUE OFF).
        (activity as? ActivityMain)?.refreshStateChip()
        val engineOn = !globalSPF.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_OFF, false)
        val trueOff = TrueOff.isOff(ctx)
        val activeMode = if (engineOn) ProfileKey.canonical(ModeSwitcher.getCurrentPowerMode()) else ""
        Thread {
            val doc = ProfileStore.doc(ctx)
            val rows = ProfileKey.ALL.map { mode ->
                ProfileRowState(
                    mode = mode,
                    title = getString(modeTitleRes(mode)),
                    summary = doc?.summary(mode, DeviceCaps.POLICIES).orEmpty(),
                    active = activeMode == mode,
                    modified = doc?.isModified(mode) ?: false
                )
            }
            val label = "${ModeSwitcher.getCurrentSourceName()} · ${ProfileController.platform()}"
            _binding?.root?.post {
                profileCardState.value = TunerProfileCardState(
                    engineOn = engineOn,
                    trueOff = trueOff,
                    sourceLabel = label,
                    profiles = rows
                )
            }
        }.start()
    }

    private fun toggleEngine(checked: Boolean) {
        if (!CheckRootStatus.isAvailable()) {
            Toast.makeText(requireContext(), R.string.profile_engine_no_root, Toast.LENGTH_SHORT).show()
            refreshProfileCardState()
            return
        }
        if (!TrueOff.guardOrToast(requireContext())) {
            refreshProfileCardState()
            return
        }
        globalSPF.edit().putBoolean(SpfConfig.GLOBAL_SPF_PROFILE_OFF, !checked).apply()
        // Turning the engine ON clears the no-root restore prompt.
        if (checked) {
            NoRootMode.clearRestorePending(requireContext())
        }
        // OFF: stock release profile + MIUI daemons + default props.
        // ON : MIUI daemons stopped right away; the saved mode is
        // re-applied immediately after (init alone would leave the
        // device on base tuning with no active mode).
        ProfileController.setEngineEnabled(requireContext(), checked)
        ModeSwitcher().clearInitedState()
        if (checked) {
            Thread {
                modeSwitcher.ensureReady()
                _binding?.root?.post {
                    updateState()
                    refreshProfileCardState()
                }
            }.start()
        } else {
            updateState()
            refreshProfileCardState()
        }
    }

    /** TRUE OFF master switch: enter/exit runs off the main thread. */
    private fun toggleTrueOff(enable: Boolean) {
        val ctx = context ?: return
        // Optimistic UI; the real flag is re-read when the work finishes.
        profileCardState.value = profileCardState.value.copy(trueOff = enable)
        Toast.makeText(
            ctx,
            getString(if (enable) R.string.true_off_entering else R.string.true_off_exiting),
            Toast.LENGTH_SHORT
        ).show()
        Thread {
            runCatching { if (enable) TrueOff.enter(ctx) else TrueOff.exit(ctx) }
            val off = TrueOff.isOff(ctx)
            _binding?.root?.post {
                profileCardState.value = profileCardState.value.copy(trueOff = off)
                Toast.makeText(
                    ctx,
                    getString(if (enable) R.string.true_off_on else R.string.true_off_off),
                    Toast.LENGTH_SHORT
                ).show()
                updateState()
                refreshProfileCardState()
            }
        }.start()
    }

    /**
     * Engine ON → apply the profile off the main thread, with a busy row
     * while it runs and a verified result afterwards (this used to run a
     * synchronous root shell on the UI thread and report nothing).
     * Engine OFF → open the editor (unchanged).
     */
    private fun onProfileClick(mode: String) {
        val ctx = context ?: return
        if (globalSPF.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_OFF, false)) {
            startActivity(
                Intent(ctx, ActivityCpuControl::class.java).putExtra("profile", mode)
            )
            return
        }
        if (!TrueOff.guardOrToast(ctx)) return
        if (applyingMode.value != null) return

        applyingMode.value = mode
        applyResult.value = ""
        Thread {
            modeSwitcher.executePowercfgMode(mode, ctx.packageName)
            val report = ProfileController.lastReport?.takeIf { it.mode == mode }
            val applied = ProfileKey.canonical(ModeSwitcher.getCurrentPowerMode()) ==
                ProfileKey.canonical(mode)
            _binding?.root?.post {
                applyingMode.value = null
                val title = getString(modeTitleRes(mode))
                applyResult.value = when {
                    !applied -> getString(R.string.profile_apply_failed, title)
                    report != null && !report.ok ->
                        getString(R.string.profile_apply_partial, title, report.mismatches.size)
                    report != null -> getString(R.string.profile_apply_ok, title, report.ops)
                    else -> getString(R.string.profile_apply_ok_plain, title)
                }
                refreshProfileCardState()
                (activity as? ActivityMain)?.refreshStateChip()
                if (!applied) offerDiagnostics(mode)
            }
        }.start()
    }

    /** Failure path: point at Diagnostics instead of dying silently. */
    private fun offerDiagnostics(mode: String) {
        val act = activity ?: return
        DialogHelper.confirm(
            act,
            getString(R.string.profile_apply_failed_title),
            getString(R.string.profile_apply_failed_msg, getString(modeTitleRes(mode))),
            DialogHelper.DialogButton(getString(R.string.menu_diagnostics), Runnable {
                startActivity(Intent(act, ActivityDiagnostics::class.java))
            }),
            DialogHelper.DialogButton(getString(R.string.profile_cancel), null)
        )
    }

    /** Row's explicit Edit affordance: open the editor, unlocking first. */
    private fun onProfileEdit(mode: String) {
        val ctx = context ?: return
        val engineOff = globalSPF.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_OFF, false)
        if (engineOff || TrueOff.isOff(ctx)) {
            startActivity(Intent(ctx, ActivityCpuControl::class.java).putExtra("profile", mode))
        } else {
            offerEngineOffToEdit(mode)
        }
    }

    /**
     * The editor is config-only (hard rule 13): while a profile runs it stays
     * locked. Instead of the old dead-end toast, offer to turn Profile OFF
     * and continue — the user keeps control instead of hitting a wall.
     */
    private fun offerEngineOffToEdit(mode: String?) {
        val act = activity ?: return
        DialogHelper.confirm(
            act,
            getString(R.string.profile_edit_locked_title),
            getString(R.string.profile_edit_locked_msg),
            DialogHelper.DialogButton(getString(R.string.profile_edit_locked_confirm), Runnable {
                toggleEngine(false)
                // toggleEngine guards root/TRUE OFF; only continue when the
                // engine really went off (otherwise the editor opens locked).
                if (globalSPF.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_OFF, false)) {
                    startActivity(
                        Intent(act, ActivityCpuControl::class.java).apply {
                            if (mode != null) putExtra("profile", mode)
                        }
                    )
                }
            }),
            DialogHelper.DialogButton(getString(R.string.profile_cancel), null)
        )
    }

    /** Tuning-source dialog: opens the profiles folder or removes an external script. */
    private fun showSourceDialog() {
        val act = activity ?: return
        if (configInstaller.outsideConfigInstalled()) {
            DialogHelper.warning(
                act,
                getString(R.string.make_choice),
                getString(R.string.schedule_remove_outside),
                Runnable {
                    configInstaller.removeOutsideConfig()
                    reStartService()
                    updateState()
                    refreshProfileCardState()
                },
                null
            )
        } else {
            val message = getString(R.string.tuning_source_help, TuningRepository.dir().absolutePath) +
                "\n\n" + getString(R.string.profile_source_folder_hint)
            DialogHelper.confirm(
                act,
                getString(R.string.tuning_source_title),
                message,
                DialogHelper.DialogButton(getString(R.string.profile_source_open_folder), Runnable {
                    TuningRepository.openFolder(requireContext())
                }),
                DialogHelper.DialogButton(getString(R.string.profile_source_close), null)
            )
        }
    }

    // -------------------------------------------------------------- dynamic card
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
                    getString(R.string.schedule_dynamic_off),
                    Runnable {
                        startActivity(Intent(context, ActivityAppConfig2::class.java))
                    },
                    null
                )
            }
        }
        content.navCpuControl.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            // Engine OFF → edit any profile. TRUE OFF → read-only viewer
            // (nothing runs, so inspecting configs is safe). Engine ON with a
            // profile running → offer to turn it OFF first (never a dead end).
            val trueOff = TrueOff.isOff(ctx)
            val engineOff = globalSPF.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_OFF, false)
            if (!trueOff && !engineOff) {
                offerEngineOffToEdit(null)
                return@setOnClickListener
            }
            startActivity(Intent(ctx, ActivityCpuControl::class.java))
        }
        if (Build.MANUFACTURER.lowercase(Locale.getDefault()) == "xiaomi") {
            content.navMiuiThermal.setOnClickListener {
                startActivity(Intent(context, ActivityMiuiThermal::class.java))
            }
        } else {
            content.navMiuiThermal.visibility = View.GONE
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

    private fun updateState() {
        val viewBinding = contentBinding ?: return

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

    override fun onResume() {
        super.onResume()
        updateState()
        refreshProfileCardState()
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
        cardServiceNoticeView = null
        cardDynamicView = null
        cardControlsView = null
    }
}

@Composable
private fun TunerScreen(
    profileState: TunerProfileCardState,
    applyingMode: String?,
    applyResult: String,
    onEngineToggle: (Boolean) -> Unit,
    onTrueOffToggle: (Boolean) -> Unit,
    onProfileClick: (String) -> Unit,
    onProfileEdit: (String) -> Unit,
    onSourceClick: () -> Unit,
    cardServiceNotice: View?,
    showServiceNotice: Boolean,
    cardDynamic: View?,
    cardControls: View?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = SceneDimens.screenH,
                end = SceneDimens.screenH,
                top = SceneDimens.screenVTop,
                bottom = SceneDimens.screenVBottom
            ),
        verticalArrangement = Arrangement.spacedBy(SceneDimens.cardGap)
    ) {
        TunerProfileCard(
            state = profileState,
            applyingMode = applyingMode,
            applyResult = applyResult,
            onEngineToggle = onEngineToggle,
            onTrueOffToggle = onTrueOffToggle,
            onProfileClick = onProfileClick,
            onProfileEdit = onProfileEdit,
            onSourceClick = onSourceClick
        )
        if (showServiceNotice) {
            MiuixCardSection(cardServiceNotice)
        }
        MiuixCardSection(
            cardDynamic,
            insideMargin = androidx.compose.foundation.layout.PaddingValues(
                start = SceneDimens.spaceS, top = SceneDimens.spaceS,
                end = SceneDimens.spaceS, bottom = SceneDimens.spaceS
            )
        )
        MiuixCardSection(
            cardControls,
            insideMargin = androidx.compose.foundation.layout.PaddingValues(
                start = SceneDimens.spaceS, top = 0.dp, end = SceneDimens.spaceS, bottom = SceneDimens.spaceS
            )
        )
    }
}

@Composable
private fun MiuixCardSection(
    view: View?,
    insideMargin: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(horizontal = SceneDimens.spaceS, vertical = 0.dp)
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
