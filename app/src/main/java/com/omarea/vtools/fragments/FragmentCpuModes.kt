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
import com.omarea.library.shell.ThermalDisguise
import com.omarea.permissions.CheckRootStatus
import com.omarea.scene_mode.CpuConfigInstaller
import com.omarea.scene_mode.ModeSwitcher
import com.omarea.store.CpuConfigStorage
import com.omarea.store.SpfConfig
import com.omarea.utils.AccessibleServiceHelper
import com.omarea.vtools.R
import com.omarea.vtools.activities.*
import com.omarea.vtools.databinding.FragmentCpuModesBinding
import com.omarea.vtools.databinding.FragmentCpuModesContentBinding
import com.omarea.vtools.dialogs.DialogConfigBackup
import com.omarea.vtools.dialogs.DialogGameSessions
import com.omarea.vtools.dialogs.DialogKernelFeatures
import com.omarea.vtools.dialogs.DialogProfileOptions
import com.omarea.vtools.dialogs.DialogRebootOptions
import java.io.File
import java.nio.charset.Charset
import java.util.*
import com.omarea.vtools.ui.compat.Card
import com.omarea.vtools.ui.compat.CardDefaults
import com.omarea.vtools.ui.compat.ColorSchemeMode
import com.omarea.vtools.ui.compat.MiuixTheme
import com.omarea.vtools.ui.compat.ThemeController

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

    companion object {
        /** Upper bound for a user-imported mode config script (200 KB). */
        private const val MAX_CONFIG_SCRIPT_BYTES = 200L * 1024L

        /** Mode ladder of the "first mode" spinner, in display order. */
        private val FIRST_MODE_LADDER = arrayOf(
            ModeSwitcher.POWERSAVE,
            ModeSwitcher.BALANCE,
            ModeSwitcher.PERFORMANCE,
            ModeSwitcher.FAST,
            ModeSwitcher.IGONED
        )

        /** Mode ladder of the "sleep mode" spinner, in display order. */
        private val SLEEP_MODE_LADDER = arrayOf(
            ModeSwitcher.POWERSAVE,
            ModeSwitcher.BALANCE,
            ModeSwitcher.PERFORMANCE,
            ModeSwitcher.IGONED
        )

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

    private fun startService() {
        AccessibleServiceHelper().stopSceneModeService(activity!!.applicationContext)
        val openSettings = Runnable {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            // Android 13 blocks accessibility services of sideloaded apps until
            // "Restricted settings" is unlocked on the app info screen.
            DialogHelper.warning(
                activity!!,
                getString(R.string.accessibility_restricted_title),
                getString(R.string.accessibility_restricted_message),
                Runnable {
                    Scene.toast(getString(R.string.accessibility_please_activate), Toast.LENGTH_SHORT)
                    openSettings.run()
                },
                openSettings
            )
        } else {
            Scene.toast(getString(R.string.accessibility_please_activate), Toast.LENGTH_SHORT)
            openSettings.run()
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
                    cardShortcuts = cardShortcutsView,
                    cardMore = cardMoreView
                )
            }
        }

        bindMode(content.cpuConfigP0, ModeSwitcher.POWERSAVE)
        bindMode(content.cpuConfigP1, ModeSwitcher.BALANCE)
        bindMode(content.cpuConfigP2, ModeSwitcher.PERFORMANCE)
        bindMode(content.cpuConfigP3, ModeSwitcher.FAST)
        bindMode(content.cpuConfigP4, ModeSwitcher.OFF)

        content.dynamicControlOpts2.initExpand(false)
        content.dynamicControlToggle.setOnClickListener {
            content.dynamicControlOpts2.toggleExpand()
            if (content.dynamicControlOpts2.isExpand) {
                (it as ImageView).setImageDrawable(ContextCompat.getDrawable(context!!, R.drawable.arrow_up))
            } else {
                (it as ImageView).setImageDrawable(ContextCompat.getDrawable(context!!, R.drawable.arrow_down))
            }
        }

        content.firstMode.run {
            when (globalSPF.getString(SpfConfig.GLOBAL_SPF_POWERCFG_FIRST_MODE, ModeSwitcher.BALANCE)) {
                ModeSwitcher.POWERSAVE -> setSelection(0)
                ModeSwitcher.BALANCE -> setSelection(1)
                ModeSwitcher.PERFORMANCE -> setSelection(2)
                ModeSwitcher.FAST -> setSelection(3)
                ModeSwitcher.IGONED -> setSelection(4)
            }

            onItemSelectedListener = ModeOnItemSelectedListener(
                globalSPF, { reStartService() },
                SpfConfig.GLOBAL_SPF_POWERCFG_FIRST_MODE,
                FIRST_MODE_LADDER,
                ModeSwitcher.DEFAULT
            )
        }

        content.sleepMode.run {
            when (globalSPF.getString(SpfConfig.GLOBAL_SPF_POWERCFG_SLEEP_MODE, ModeSwitcher.POWERSAVE)) {
                ModeSwitcher.POWERSAVE -> setSelection(0)
                ModeSwitcher.BALANCE -> setSelection(1)
                ModeSwitcher.PERFORMANCE -> setSelection(2)
                ModeSwitcher.IGONED -> setSelection(3)
            }
            onItemSelectedListener = ModeOnItemSelectedListener(
                globalSPF, { },
                SpfConfig.GLOBAL_SPF_POWERCFG_SLEEP_MODE,
                SLEEP_MODE_LADDER,
                ModeSwitcher.POWERSAVE
            )
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
        // Activate accessibility service button
        content.navSceneServiceNotActive.setOnClickListener {
            startService()
        }
        if (CheckRootStatus.lastCheckResult) {
            content.navMore.visibility = View.VISIBLE
            content.navProfileOptions.setOnClickListener {
                DialogProfileOptions(activity!!).show()
            }
            content.navBackup.setOnClickListener {
                DialogConfigBackup(activity!!).show()
            }
            content.navReboot.setOnClickListener {
                DialogRebootOptions(activity!!).show()
            }
            content.navKernelFeatures.setOnClickListener {
                DialogKernelFeatures(activity!!).show()
            }
            content.navGameSessions.setOnClickListener {
                DialogGameSessions(activity!!).show()
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

        if (!modeSwitcher.modeConfigCompleted() && configInstaller.dynamicSupport(context!!)) {
            installConfig(false)
        }
        // Extreme performance, currently only available on the 888
        content.extremePerformance.visibility = if (ThermalDisguise().supported()) View.VISIBLE else View.GONE
        content.extremePerformanceOn.setOnClickListener {
            val isChecked = (it as CompoundButton).isChecked
            if (isChecked) {
                ThermalDisguise().disableMessage()
            } else {
                ThermalDisguise().resumeMessage()
            }
        }
    }

    // Choose config source
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
            // TODO: clear all previous custom configs, not just the external one
            if (outsideOverrode()) {
                configInstaller.removeOutsideConfig()
            }

            getOnlineConfig()

            dialog.dismiss()
        }
        view.findViewById<View>(R.id.source_custom).setOnClickListener {
            // TODO: clear all previous custom configs, not just the external one
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

    /**
     * Persists the mode the user picked in a spinner.
     *
     * The two spinners differ only in their mode ladder and in which preference
     * they write to, so they share this listener instead of two near-identical
     * copies that had already drifted apart (different defaults, different
     * ladder lengths).
     *
     * @param spfKey preference key the selection is written to
     * @param modes mode identifiers ordered as they appear in the spinner
     * @param defaultValue value assumed when the preference is unset
     */
    private class ModeOnItemSelectedListener(
        private val globalSPF: SharedPreferences,
        private val runnable: Runnable,
        private val spfKey: String,
        private val modes: Array<String>,
        private val defaultValue: String
    ) : AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: AdapterView<*>?) {
        }

        @SuppressLint("ApplySharedPref")
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            val mode = modes.getOrElse(position) { defaultValue }
            if (globalSPF.getString(spfKey, defaultValue) != mode) {
                globalSPF.edit().putString(spfKey, mode).apply()
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
            } else if (mode == ModeSwitcher.FAST && !CpuConfigStorage(context!!).exists(ModeSwitcher.FAST)) {
                // Custom without a saved config: open CPU Control to create one.
                openCustomEditor()
            } else {
                modeSwitcher.executePowercfgMode(mode, context!!.packageName)
                updateState(binding.cpuConfigP0, ModeSwitcher.POWERSAVE)
                updateState(binding.cpuConfigP1, ModeSwitcher.BALANCE)
                updateState(binding.cpuConfigP2, ModeSwitcher.PERFORMANCE)
                updateState(binding.cpuConfigP3, ModeSwitcher.FAST)
                updateState(binding.cpuConfigP4, ModeSwitcher.OFF)
            }
        }
        if (mode == ModeSwitcher.FAST) {
            // Long-press edits the saved Custom parameters at any time.
            button.setOnLongClickListener {
                openCustomEditor()
                true
            }
        }
    }

    private fun openCustomEditor() {
        val intent = Intent(context, ActivityCpuControl::class.java)
        intent.putExtra("cpuModeName", ModeSwitcher.FAST)
        startActivity(intent)
    }

    private fun updateState() {
        val viewBinding = contentBinding ?: return
        val outsideInstalled = configInstaller.outsideConfigInstalled()
        configFileInstalled = outsideInstalled || configInstaller.insideConfigInstalled()
        author = ModeSwitcher.getCurrentSource()

        viewBinding.configAuthor.text = ModeSwitcher.getCurrentSourceName()

        updateState(viewBinding.cpuConfigP0, ModeSwitcher.POWERSAVE)
        updateState(viewBinding.cpuConfigP1, ModeSwitcher.BALANCE)
        updateState(viewBinding.cpuConfigP2, ModeSwitcher.PERFORMANCE)
        updateState(viewBinding.cpuConfigP3, ModeSwitcher.FAST)
        updateState(viewBinding.cpuConfigP4, ModeSwitcher.OFF)
        val serviceState = AccessibleServiceHelper().serviceRunning(context!!)
        val serviceNoticeVisible = if (serviceState) View.GONE else View.VISIBLE
        showServiceNotice.value = serviceNoticeVisible == View.VISIBLE
        viewBinding.navSceneServiceNotActive.visibility = serviceNoticeVisible
        cardServiceNoticeView?.visibility = serviceNoticeVisible

        viewBinding.extremePerformanceOn.isChecked = ThermalDisguise().isDisabled()
    }

    private fun updateState(button: View, mode: String) {
        val isCurrent = ModeSwitcher.getCurrentPowerMode() == mode
        button.alpha = if (configFileInstalled && isCurrent) 1f else 0.4f
    }

    override fun onResume() {
        super.onResume()

        val currentAuthor = author
        updateState()

        // Restart the background service if the config author changed
        if (!currentAuthor.isEmpty() && currentAuthor != author) {
            reStartService()
        }
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
        // Native Android file picker
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
            KeepShellPublic.doCmdSync("cp \"${file.absolutePath}\" \"$innerPath\"\nchmod 777 \"$innerPath\"")
            val tmpFile = File(innerPath)
            if (tmpFile.exists() && tmpFile.canRead()) {
                val lines = tmpFile.readText(Charset.defaultCharset()).trimStart().replace("\r", "")
                KeepShellPublic.doCmdSync("rm \"$innerPath\"")
                return lines
            }
        }
        return null
    }

    private fun getOnlineConfig() {
        DialogHelper.alert(this.activity!!,
                "Notice",
                "Scene no longer provides online config scripts. If needed, use the optimization module by \"yc9559\" (flash it with your root manager), then reboot to use scheduling switches in Scene.") {
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
            if (file.length() > MAX_CONFIG_SCRIPT_BYTES) {
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

    // Install the frequency tuning file
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
        if (cardMore?.visibility == View.VISIBLE) {
            MiuixCardSection(cardMore)
        }
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
