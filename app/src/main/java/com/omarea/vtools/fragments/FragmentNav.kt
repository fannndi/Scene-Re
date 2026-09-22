package com.omarea.vtools.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.omarea.common.ui.ThemeMode
import com.omarea.kr.KrScriptConfig
import com.omarea.shell_utils.BackupRestoreUtils
import com.omarea.vtools.R
import com.omarea.vtools.activities.*
import com.omarea.vtools.privilege.PrivilegeManager
import com.projectkr.shell.OpenPageHelper
import com.omarea.vtools.databinding.FragmentNavBinding
import com.omarea.vtools.ui.overview.OverviewMenu
import com.omarea.vtools.ui.overview.overviewNavTitleRes
import com.omarea.vtools.ui.theme.SceneTheme

class FragmentNav : Fragment() {
    private lateinit var themeMode: ThemeMode
    private var _binding: FragmentNavBinding? = null
    private val binding get() = _binding!!
    /**
     * Navigation entries that can only work with uid 0.
     *
     * This must stay in sync with `requiresRoot` in [OverviewMenu]; when the two disagree a card
     * either looks available and then fails, or looks blocked without ever being clickable.
     * Membership means "needs root", not "is unavailable": the entry stays visible and explains
     * itself when tapped.
     */
    private val rootRequiredIds = setOf(
        R.id.nav_core_control,
        R.id.nav_processes,
        R.id.nav_fps_chart,
        R.id.nav_applictions,
        R.id.nav_img,
        R.id.nav_additional,
        R.id.nav_additional_all,
        R.id.nav_app_magisk,
        R.id.nav_modules
    )

    companion object {
        fun createPage(themeMode: ThemeMode): Fragment {
            val fragment = FragmentNav()
            fragment.themeMode = themeMode;
            return fragment
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        _binding = FragmentNavBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!::themeMode.isInitialized) {
            themeMode = (activity as? ActivityBase)?.themeMode ?: ThemeMode()
        }
        binding.composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        binding.composeView.setContent {
            SceneTheme(mode = themeMode) {
                OverviewMenu(
                    isRootAvailable = PrivilegeManager.isPrivileged,
                    onItemClick = { handleNavClick(it) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isDetached) {
            return
        }
        activity!!.title = getString(R.string.app_name)
    }

    /** The visible title of a navigation entry, sourced from the shared Overview model. */
    private fun navTitle(id: Int): String {
        val titleRes = overviewNavTitleRes(id)
        return if (titleRes != 0) getString(titleRes) else getString(R.string.app_name)
    }

    private fun handleNavClick(id: Int) {
        if (!PrivilegeManager.hasRootAccess && rootRequiredIds.contains(id)) {
            Toast.makeText(
                context,
                getString(R.string.menu_root_required_message, navTitle(id)),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        when (id) {
            R.id.nav_privilege_mode -> {
                val intent = Intent(context, ActivityPrivilege::class.java)
                startActivity(intent)
                return
            }
            R.id.nav_setup -> {
                val intent = Intent(context, com.omarea.vtools.setup.ActivitySetup::class.java)
                startActivity(intent)
                return
            }
            R.id.nav_applictions -> {
                val intent = Intent(context, ActivityApplistions::class.java)
                startActivity(intent)
                return
            }
            R.id.nav_charge -> {
                val intent = Intent(context, ActivityCharge::class.java)
                startActivity(intent)
                return
            }
            R.id.nav_power_utilization -> {
                val intent = Intent(context, ActivityPowerUtilization::class.java)
                startActivity(intent)
                return
            }
            R.id.nav_img -> {
                if (BackupRestoreUtils.isSupport()) {
                    val intent = Intent(context, ActivityImg::class.java)
                    startActivity(intent)
                } else {
                    Toast.makeText(context, "This feature is not supported on your device.", Toast.LENGTH_SHORT).show()
                }
                return
            }
            R.id.nav_battery_stats -> {
                val intent = Intent(context, ActivityPowerUtilization::class.java)
                startActivity(intent)
                return
            }
            R.id.nav_core_control -> {
                val intent = Intent(context, ActivityCpuControl::class.java)
                startActivity(intent)
                return
            }
            R.id.nav_miui_thermal -> {
                val intent = Intent(context, ActivityMiuiThermal::class.java)
                startActivity(intent)
                return
            }
            R.id.nav_app_scene -> {
                val intent = Intent(context, ActivityAppConfig2::class.java)
                startActivity(intent)
                return
            }
            R.id.nav_app_magisk -> {
                val intent = Intent(context, ActivityMagisk::class.java)
                startActivity(intent)
                return
            }
            R.id.nav_modules -> {
                val intent = Intent(context, ActivityModules::class.java)
                startActivity(intent)
                return
            }
            R.id.nav_processes -> {
                val intent = Intent(context, ActivityProcess::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return
            }
            R.id.nav_fps_chart -> {
                val intent = Intent(context, ActivityFpsChart::class.java)
                startActivity(intent)
                return
            }
            R.id.nav_additional -> {
                val intent = Intent(context, ActivityAddin::class.java)
                startActivity(intent)
                return
            }
            R.id.nav_additional_all -> {
                val krScriptConfig = KrScriptConfig().init(context!!)
                val activity = activity!!
                krScriptConfig.pageListConfig?.run {
                    OpenPageHelper(activity).openPage(this.apply {
                        title = getString(R.string.menu_additional)
                    })
                }
                return
            }
            else -> {}
        }
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
