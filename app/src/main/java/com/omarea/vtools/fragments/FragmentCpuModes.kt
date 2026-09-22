package com.omarea.vtools.fragments

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.omarea.Scene
import com.omarea.common.shared.FilePathResolver
import com.omarea.common.shared.FileWrite
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.ui.DialogHelper
import com.omarea.common.ui.ThemeMode
import com.omarea.data.EventBus
import com.omarea.data.EventType
import com.omarea.krscript.model.PageNode
import com.omarea.scene_mode.CpuConfigInstaller
import com.omarea.scene_mode.ModeSwitcher
import com.omarea.store.SpfConfig
import com.omarea.utils.AccessibleServiceHelper
import com.omarea.utils.ShellSafety
import com.omarea.vtools.R
import com.omarea.vtools.activities.*
import com.projectkr.shell.OpenPageHelper
import com.omarea.vtools.databinding.FragmentCpuModesBinding
import com.omarea.vtools.databinding.FragmentCpuModesContentBinding
import com.omarea.vtools.privilege.PrivilegeManager
import com.omarea.vtools.ui.theme.SceneTheme
import java.io.File
import java.nio.charset.Charset
import java.util.*

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
    private var cardModesView: View? = null
    private var cardServiceNoticeView: View? = null
    private var cardDynamicView: View? = null
    private var cardShortcutsView: View? = null
    private var cardMoreView: View? = null

    /** Incremented per [updateState] read so a stale background result cannot overwrite a newer one. */
    private var stateRequest = 0

    companion object {
        private const val TAG = "SceneAccessibility"

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
     * Bring the Scene mode accessibility service up.
     *
     * This never calls stopSceneModeService(). "Stopping" the service means removing it from the
     * secure setting enabled_accessibility_services, which is a persistent, user-visible permission
     * change - so the old code tore down a grant the user had already given and then asked them to
     * give it again, which is why the service kept showing up as not activated.
     *
     * With root or Shizuku the shell can grant it outright, so the user never has to leave the app.
     * Only when the shell cannot do it do we fall back to the system screen that carries the switch.
     * Both probes run off the UI thread because they touch the shell and parse config files.
     */
    private fun startService() {
        val activity = activity ?: return
        val appContext = activity.applicationContext
        Thread {
            val helper = AccessibleServiceHelper()
            if (helper.serviceRunning(appContext)) {
                activity.runOnUiThread { if (isAdded) updateState() }
                return@Thread
            }
            val startedByShell = PrivilegeManager.isPrivileged && helper.startSceneModeService(appContext)
            activity.runOnUiThread {
                if (!isAdded) {
                    return@runOnUiThread
                }
                if (startedByShell) {
                    updateState()
                } else {
                    openAccessibilitySettings(PrivilegeManager.isPrivileged)
                }
            }
        }.start()
    }

    /**
     * Last resort when the shell backend cannot grant the service: send the user to the screen that
     * actually has the toggle. Settings.ACTION_ACCESSIBILITY_SETTINGS lists the service; the app
     * details screen does not.
     *
     * @param shellTried true when we had a privileged shell and the grant still did not take, which
     *   means the write was refused rather than that we never had a backend to try.
     */
    private fun openAccessibilitySettings(shellTried: Boolean) {
        Scene.toast(
            getString(if (shellTried) R.string.accessibility_grant_failed else R.string.accessibility_please_activate),
            Toast.LENGTH_LONG
        )
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (ex: Exception) {
            Log.w(TAG, "ACCESSIBILITY_SETTINGS unavailable: ${ex.message}")
            Scene.toast(getString(R.string.accessibility_settings_unavailable), Toast.LENGTH_LONG)
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
        cardShortcutsView = detachFromParent(content.cpuModesCardShortcuts)
        cardMoreView = detachFromParent(content.navMore)

        binding.composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        binding.composeView.setContent {
            SceneTheme(mode = themeMode) {
                TunerScreen(
                    cardModes = cardModesView,
                    cardServiceNotice = cardServiceNoticeView,
                    showServiceNotice = showServiceNotice.value,
                    cardDynamic = cardDynamicView,
                    cardShortcuts = cardShortcutsView,
                    cardMore = cardMoreView
                )
            }
        }

        bindMode(content.cpuConfigP0, ModeSwitcher.POWERSAVE)
        bindMode(content.cpuConfigP1, ModeSwitcher.BALANCE)
        bindMode(content.cpuConfigP2, ModeSwitcher.PERFORMANCE)
        bindMode(content.cpuConfigP3, ModeSwitcher.FAST)

        content.dynamicControl.setOnClickListener {
            val switch = it as Switch
            if (!switch.isChecked) {
                globalSPF.edit().putBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL, false).apply()
                reStartService()
                return@setOnClickListener
            }

            // Turning it on. Both prerequisites are expensive - one parses the installed config
            // files, the other runs a shell command - so they are checked off the UI thread;
            // doing it inline made the switch visibly hang on a slow shell.
            val appContext = context ?: return@setOnClickListener
            val hostActivity = activity ?: return@setOnClickListener
            Thread {
                val configReady = modeSwitcher.modeConfigCompleted()
                val serviceUp = AccessibleServiceHelper().serviceRunning(appContext)
                hostActivity.runOnUiThread {
                    val binding = contentBinding ?: return@runOnUiThread
                    if (!isAdded) {
                        return@runOnUiThread
                    }
                    when {
                        !configReady -> {
                            // Revert silently was the old behaviour and it read as a broken switch;
                            // the alert already says which modes are still missing.
                            binding.dynamicControl.isChecked = false
                            DialogHelper.alert(appContext, getString(R.string.sorry), getString(R.string.schedule_unfinished))
                        }
                        !serviceUp -> {
                            binding.dynamicControl.isChecked = false
                            Scene.toast(getString(R.string.schedule_scene_need_service), Toast.LENGTH_LONG)
                            startService()
                        }
                        else -> {
                            globalSPF.edit().putBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL, true).apply()
                            reStartService()
                        }
                    }
                }
            }.start()
        }
        content.dynamicControlOpts2.initExpand(false)
        content.dynamicControl.setOnCheckedChangeListener { _, isChecked ->
            content.dynamicControlOpts.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        content.dynamicControlToggle.setOnClickListener {
            content.dynamicControlOpts2.toggleExpand()
            syncDynamicChevron()
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

        val sourceClick = object : View.OnClickListener {
            override fun onClick(it: View) {
                if (configInstaller.outsideConfigInstalled()) {
                    if (configInstaller.dynamicSupport(context!!)) {
                        DialogHelper.warning(
                            activity!!,
                            getString(R.string.make_choice),
                            getString(R.string.schedule_remove_outside),
                            {
                                configInstaller.removeOutsideConfig()
                                reStartService()
                                updateState()
                                chooseConfigSource()
                            })
                    } else {
                        Scene.toast(getString(R.string.schedule_unofficial), Toast.LENGTH_LONG)
                    }
                } else if (configInstaller.dynamicSupport(context!!)) {
                    chooseConfigSource()
                } else {
                    Scene.toast(getString(R.string.schedule_unsupported), Toast.LENGTH_LONG)
                }
            }
        }
        content.configAuthorIcon.setOnClickListener(sourceClick)
        content.configAuthor.setOnClickListener(sourceClick)

        content.navBatteryStats.setOnClickListener {
            val intent = Intent(context, ActivityPowerUtilization::class.java)
            startActivity(intent)
        }
        content.navAppScene.setOnClickListener {
            if (!AccessibleServiceHelper().serviceRunning(context!!)) {
                startService()
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
        // Activate accessibility service button
        content.navSceneServiceNotActive.setOnClickListener {
            startService()
        }
        // Auto skip ads
        content.navSkipAd.setOnClickListener {
            if (AccessibleServiceHelper().serviceRunning(context!!)) {
                val intent = Intent(context, ActivityAutoClick::class.java)
                startActivity(intent)
            } else {
                startService()
            }
        }
        if (PrivilegeManager.isPrivileged) {
            content.navMore.visibility = View.VISIBLE
            if (Build.MANUFACTURER.lowercase(Locale.getDefault()) == "xiaomi") {
                content.navThermal.setOnClickListener {
                    val pageNode = PageNode("").apply {
                        title = "MIUI only"
                        pageConfigPath = "file:///android_asset/kr-script/miui/miui.xml"
                    }
                    OpenPageHelper(activity!!).openPage(pageNode)
                }
            } else {
                content.navThermal.visibility = View.GONE
            }
            content.navProcesses.setOnClickListener {
                val intent = Intent(context, ActivityProcess::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
            content.navFreeze.setOnClickListener {
                if (AccessibleServiceHelper().serviceRunning(context!!)) {
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.setClassName(
                        "com.omarea.vtools", "com.omarea.vtools.activities.ActivityFreezeApps2"
                    )
                    startActivity(intent)
                } else {
                    startService()
                }
            }
        }

        // First run: make sure a scheduling config is in place. The check and the install both run
        // on a worker thread inside installConfig, so nothing here blocks the UI.
        installConfig(false, onlyIfNeeded = true)
    }

    // Select config source
    private fun chooseConfigSource() {
        val view = layoutInflater.inflate(R.layout.dialog_powercfg_source, null)
        val dialog = DialogHelper.customDialog(activity!!, view)

        val conservative = view.findViewById<View>(R.id.source_official_conservative)
        val active = view.findViewById<View>(R.id.source_official_active)

        val cpuConfigInstaller = CpuConfigInstaller()
        if (cpuConfigInstaller.dynamicSupport(context!!)) {
            conservative.setOnClickListener {
                if (configInstaller.outsideConfigInstalled()) {
                    configInstaller.removeOutsideConfig()
                }
                installConfig(false)

                dialog.dismiss()
            }
            active.setOnClickListener {
                if (configInstaller.outsideConfigInstalled()) {
                    configInstaller.removeOutsideConfig()
                }
                installConfig(true)

                dialog.dismiss()
            }
        } else {
            conservative.visibility = View.GONE
            active.visibility = View.GONE
        }

        view.findViewById<View>(R.id.source_import).setOnClickListener {
            chooseLocalConfig()

            dialog.dismiss()
        }
        view.findViewById<View>(R.id.source_download).setOnClickListener {
            // TODO: clear all previous custom configs, not just the external ones
            if (outsideOverrode()) {
                configInstaller.removeOutsideConfig()
            }

            getOnlineConfig()

            dialog.dismiss()
        }
        view.findViewById<View>(R.id.source_custom).setOnClickListener {
            // TODO: clear all previous custom configs, not just the external ones
            if (outsideOverrode()) {
                configInstaller.removeOutsideConfig()
            }
            globalSPF.edit().putString(SpfConfig.GLOBAL_SPF_PROFILE_SOURCE, ModeSwitcher.SOURCE_SCENE_CUSTOM).apply()
            updateState()

            dialog.dismiss()
        }
    }

    private fun bindSPF(checkBox: CompoundButton, spf: SharedPreferences, prop: String, defValue: Boolean = false, restartService: Boolean = false) {
        checkBox.isChecked = spf.getBoolean(prop, defValue)
        checkBox.setOnCheckedChangeListener { _, isChecked ->
            spf.edit().putBoolean(prop, isChecked).apply()
            if (restartService) {
                reStartService()
            }
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
            val appContext = context ?: return@setOnClickListener
            val hostActivity = activity ?: return@setOnClickListener
            val packageName = appContext.packageName
            // Applying a mode runs powercfg.sh through the shell, so the whole path stays off the UI
            // thread - the previous version blocked the main thread for the duration of the script.
            Thread {
                val needsConfirm = mode == ModeSwitcher.FAST &&
                        ModeSwitcher.getCurrentSource() == ModeSwitcher.SOURCE_OUTSIDE_UPERF
                hostActivity.runOnUiThread {
                    if (!isAdded) {
                        return@runOnUiThread
                    }
                    if (needsConfirm) {
                        DialogHelper.warning(
                                hostActivity,
                                getString(R.string.please_notice),
                                getString(R.string.schedule_uperf_fast)
                        ) {
                            applyMode(mode, packageName)
                        }
                    } else {
                        applyMode(mode, packageName)
                    }
                }
            }.start()
        }
    }

    /**
     * Runs the scheduling profile for [mode] off the UI thread, then refreshes the screen.
     *
     * `executePowercfgMode` reports failure only through Log.e, so a missing config file made the
     * tap look like it did nothing. A successful apply caches the mode, so comparing
     * [ModeSwitcher.getCurrentPowerMode] against what we asked for is a reliable success test.
     */
    private fun applyMode(mode: String, packageName: String) {
        Thread {
            modeSwitcher.executePowercfgMode(mode, packageName)
            val applied = ModeSwitcher.getCurrentPowerMode() == mode
            activity?.runOnUiThread {
                if (!isAdded) {
                    return@runOnUiThread
                }
                if (!applied) {
                    Scene.toast(getString(R.string.schedule_apply_failed), Toast.LENGTH_LONG)
                }
                updateState()
            }
        }.start()
    }

    /**
     * Read everything the screen needs, then apply it in one pass.
     *
     * Every read here is a shell round-trip - `outsideConfigInstalled()`, `getCurrentSource()`,
     * `getCurrentPowerMode()` and `modeConfigCompleted()` each spawn a command - and this runs on
     * every onResume. Doing them inline blocked the main thread for as long as the shell took, which
     * on a slow or cold shell is very visible when switching to this tab.
     *
     * [stateRequest] discards a stale read that finishes after a newer one.
     */
    private fun updateState() {
        if (contentBinding == null) {
            return
        }
        val appContext = context ?: return
        val request = ++stateRequest
        Thread {
            val snapshot = TunerState(
                configFileInstalled = configInstaller.outsideConfigInstalled() || configInstaller.insideConfigInstalled(),
                author = ModeSwitcher.getCurrentSource(),
                authorName = ModeSwitcher.getCurrentSourceName(),
                currentMode = ModeSwitcher.getCurrentPowerMode(),
                serviceRunning = AccessibleServiceHelper().serviceRunning(appContext),
                modeConfigCompleted = modeSwitcher.modeConfigCompleted()
            )
            activity?.runOnUiThread {
                if (request == stateRequest && isAdded) {
                    applyState(snapshot)
                }
            }
        }.start()
    }

    /** Applies a [TunerState] snapshot. Runs on the UI thread. */
    private fun applyState(state: TunerState) {
        val viewBinding = contentBinding ?: return

        // A config source that changed while this screen was in the background has to be picked up
        // by the running service, otherwise it keeps applying the previous profile.
        val authorChanged = author.isNotEmpty() && author != state.author
        val dynamicWasOn = viewBinding.dynamicControl.isChecked

        configFileInstalled = state.configFileInstalled
        author = state.author
        viewBinding.configAuthor.text = state.authorName

        updateState(viewBinding.cpuConfigP0, ModeSwitcher.POWERSAVE, state)
        updateState(viewBinding.cpuConfigP1, ModeSwitcher.BALANCE, state)
        updateState(viewBinding.cpuConfigP2, ModeSwitcher.PERFORMANCE, state)
        updateState(viewBinding.cpuConfigP3, ModeSwitcher.FAST, state)

        val dynamicControl = globalSPF.getBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL, SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL_DEFAULT)
        viewBinding.dynamicControl.isChecked = dynamicControl && state.serviceRunning
        val serviceNoticeVisible = if (state.serviceRunning) View.GONE else View.VISIBLE
        showServiceNotice.value = serviceNoticeVisible == View.VISIBLE
        viewBinding.navSceneServiceNotActive.visibility = serviceNoticeVisible
        cardServiceNoticeView?.visibility = serviceNoticeVisible

        if (dynamicControl && !state.modeConfigCompleted) {
            // Dynamic response cannot do anything without a complete config, so it is turned back
            // off - but say so, rather than leaving the user to wonder why their switch reset.
            globalSPF.edit().putBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL, false).apply()
            viewBinding.dynamicControl.isChecked = false
            Scene.toast(getString(R.string.schedule_dynamic_disabled), Toast.LENGTH_LONG)
            reStartService()
        } else if (authorChanged && dynamicWasOn && state.serviceRunning) {
            reStartService()
        }

        viewBinding.dynamicControlOpts.postDelayed({
            val postBinding = contentBinding ?: return@postDelayed
            postBinding.dynamicControlOpts.visibility = if (postBinding.dynamicControl.isChecked) View.VISIBLE else View.GONE
            syncDynamicChevron()
        }, 15)
    }

    /** Keeps the expand chevron in step with the panel it controls. */
    private fun syncDynamicChevron() {
        val binding = contentBinding ?: return
        val icon = if (binding.dynamicControlOpts2.isExpand) R.drawable.arrow_up else R.drawable.arrow_down
        binding.dynamicControlToggle.setImageDrawable(ContextCompat.getDrawable(binding.root.context, icon))
    }

    /** Immutable snapshot of everything the Adjust screen displays, read off the UI thread. */
    private class TunerState(
        val configFileInstalled: Boolean,
        val author: String,
        val authorName: String,
        val currentMode: String,
        val serviceRunning: Boolean,
        val modeConfigCompleted: Boolean
    )

    /**
     * Reflect the applied mode on one mode card.
     *
     * An unselected card still has to be readable - the user picks a mode by reading these labels -
     * so it is dimmed only to 0.6, and the active card additionally gets a white outline. The old
     * 0.4 with no outline left "Power saving" / "Performance" / "Extreme speed" barely legible and
     * gave no cue other than brightness.
     */
    private fun updateState(button: View, mode: String, state: TunerState) {
        val selected = state.configFileInstalled && state.currentMode == mode
        button.alpha = if (selected) 1f else 0.6f
        button.foreground = if (selected) {
            ContextCompat.getDrawable(button.context, R.drawable.powercfg_card_selected)
        } else {
            null
        }
    }

    override fun onResume() {
        super.onResume()
        // The "config author changed while we were away" restart is handled inside applyState, which
        // is also reached from every other refresh path.
        updateState()
    }

    private val configInstaller = CpuConfigInstaller()

    // Whether to use the built-in file picker
    private var useInnerFileChooser = false
    private val configFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            return@registerForActivityResult
        }
        val data = result.data ?: return@registerForActivityResult
        val context = context ?: return@registerForActivityResult
        // Stock Android file picker
        if (Build.VERSION.SDK_INT >= 30 && !useInnerFileChooser) {
            val absPath = FilePathResolver().getPath(activity, data.data)
            if (absPath != null) {
                if (absPath.endsWith(".sh")) {
                    installLocalConfig(absPath)
                } else {
                    Toast.makeText(context, "Invalid file (should be a .sh file)!", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "Selected file not found!", Toast.LENGTH_SHORT).show()
            }
        } else { // Scene built-in file picker
            if (data.extras?.containsKey("file") != true) {
                return@registerForActivityResult
            }
            val path = data.extras!!.getString("file")!!
            installLocalConfig(path)
        }
    }
    private fun chooseLocalConfig() {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            useInnerFileChooser = false
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "*/*"
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            configFileLauncher.launch(intent)
        } else {
            useInnerFileChooser = true
            try {
                val intent = Intent(this.context, ActivityFileSelector::class.java)
                intent.putExtra("extension", "sh")
                configFileLauncher.launch(intent)
            } catch (ex: Exception) {
                Toast.makeText(context, "Failed to launch built-in file picker!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openUrl(link: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (ex: Exception) {
        }
    }

    private fun readFileLines(file: File): String? {
        if (file.canRead()) {
            return file.readText(Charset.defaultCharset()).trimStart().replace("\r", "")
        } else {
            val innerPath = FileWrite.getPrivateFilePath(context!!, "powercfg.tmp")
            KeepShellPublic.doCmdSync("cp ${ShellSafety.quote(file.absolutePath)} ${ShellSafety.quote(innerPath)}\nchmod 0644 ${ShellSafety.quote(innerPath)}")
            val tmpFile = File(innerPath)
            if (tmpFile.exists() && tmpFile.canRead()) {
                val lines = tmpFile.readText(Charset.defaultCharset()).trimStart().replace("\r", "")
                KeepShellPublic.doCmdSync("rm -f ${ShellSafety.quote(innerPath)}")
                return lines
            }
        }
        return null
    }

    private fun getOnlineConfig() {
        DialogHelper.alert(this.activity!!,
                "Notice",
                "Scene no longer provides online config scripts. If needed, use the optimization module by \"yc9559\" and flash it with Magisk, then reboot to use scheduling switches in Scene.") {
            openUrl("https://github.com/yc9559/uperf")
        }

        /*
        var i = 0
        DialogHelper.animDialog(AlertDialog.Builder(context)
                .setTitle(getString(R.string.config_online_options))
                .setCancelable(true)
                .setSingleChoiceItems(
                        arrayOf(
                                getString(R.string.online_config_v1),
                                getString(R.string.online_config_v2)
                        ), 0) { _, which ->
                    i = which
                }
                .setNegativeButton(R.string.btn_confirm) { _, _ ->
                    if (i == 0) {
                        getOnlineConfigV1()
                    } else if (i == 1) {
                        getOnlineConfigV2()
                    }
                })
         */
    }

    private fun installLocalConfig(path: String) {
        if (!path.endsWith(".sh")) {
            Toast.makeText(context, "This seems to be an invalid script file!", Toast.LENGTH_LONG).show()
            return
        }

        val file = File(path)
        if (file.exists()) {
            if (file.length() > 200 * 1024) {
                Toast.makeText(context, "File too large; config scripts must be <= 200KB!", Toast.LENGTH_LONG).show()
                return
            }
            val lines = readFileLines(file)
            if (lines == null) {
                Toast.makeText(context, "Scene cannot read this file!", Toast.LENGTH_LONG).show()
                return
            }
            val configStar = lines.split("\n").firstOrNull()
            if (configStar != null && (configStar.startsWith("#!/") || lines.contains("echo "))) {
                if (configInstaller.installCustomConfig(context!!, lines, ModeSwitcher.SOURCE_SCENE_IMPORT)) {
                    configInstalled()
                } else {
                    Toast.makeText(context, "Failed to install config script. Please retry.", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(context, "This seems to be an invalid script file!", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(context, "Selected file not found!", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Installs the built-in scheduling config.
     *
     * The support check and the install both touch the shell and the filesystem, so this runs off
     * the UI thread and the screen refreshes once it lands.
     *
     * @param onlyIfNeeded skip the work when a complete config is already installed, which is what
     *   the first-run path wants.
     */
    private fun installConfig(active: Boolean, onlyIfNeeded: Boolean = false) {
        val appContext = context ?: return
        Thread {
            if (onlyIfNeeded && modeSwitcher.modeConfigCompleted()) {
                return@Thread
            }
            if (!configInstaller.dynamicSupport(appContext)) {
                if (!onlyIfNeeded) {
                    activity?.runOnUiThread { Scene.toast(getString(R.string.not_support_config), Toast.LENGTH_LONG) }
                }
                return@Thread
            }
            configInstaller.installOfficialConfig(appContext, "", active)
            activity?.runOnUiThread {
                if (isAdded) {
                    configInstalled()
                }
            }
        }.start()
    }

    private fun configInstalled() {
        updateState()
        reStartService()
    }

    private fun outsideOverrode(): Boolean {
        if (configInstaller.outsideConfigInstalled()) {
            DialogHelper.helpInfo(activity!!, "You need to delete the external config first because Scene will prioritize it.")
            return true
        }
        return false
    }

    /**
     * Restart the accessibility service
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
        cardMoreView = null
    }
}

@Composable
private fun TunerScreen(
    cardModes: View?,
    cardServiceNotice: View?,
    showServiceNotice: Boolean,
    cardDynamic: View?,
    cardShortcuts: View?,
    cardMore: View?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CardSection(
            cardModes,
            insideMargin = androidx.compose.foundation.layout.PaddingValues(
                start = 4.dp,
                top = 0.dp,
                end = 4.dp,
                bottom = 8.dp
            )
        )
        if (showServiceNotice) {
            CardSection(cardServiceNotice)
        }
        CardSection(
            cardDynamic,
            insideMargin = androidx.compose.foundation.layout.PaddingValues(
                start = 8.dp,
                top = 8.dp,
                end = 8.dp,
                bottom = 8.dp
            )
        )
        CardSection(
            cardShortcuts,
            insideMargin = androidx.compose.foundation.layout.PaddingValues(
                start = 8.dp,
                top = 0.dp,
                end = 8.dp,
                bottom = 8.dp
            )
        )
        if (cardMore?.visibility == View.VISIBLE) {
            CardSection(cardMore)
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun CardSection(
    view: View?,
    insideMargin: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
) {
    if (view == null) {
        return
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        AndroidView(
            modifier = Modifier.padding(insideMargin),
            factory = {
                view.apply {
                    layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                }
            }
        )
    }
}
