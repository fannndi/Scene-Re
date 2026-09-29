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
import com.omarea.permissions.CheckRootStatus
import com.omarea.vtools.R
import com.omarea.vtools.activities.*
import com.projectkr.shell.OpenPageHelper
import com.omarea.vtools.databinding.FragmentNavBinding
import com.omarea.vtools.ui.overview.OverviewMenu
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class FragmentNav : Fragment() {
    private lateinit var themeMode: ThemeMode
    private var _binding: FragmentNavBinding? = null
    private val binding get() = _binding!!
    private val rootRequiredIds = setOf(
        R.id.nav_processes,
        R.id.nav_fps_chart,
        R.id.nav_charge,
        R.id.nav_power_utilization,
        R.id.nav_diagnostics,
        R.id.nav_additional_all
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
            val controller = ThemeController(
                if (themeMode.isDarkMode) {
                    ColorSchemeMode.Dark
                } else {
                    ColorSchemeMode.Light
                }
            )
            MiuixTheme(controller = controller) {
                OverviewMenu(
                    isRootAvailable = CheckRootStatus.lastCheckResult,
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

    private fun handleNavClick(id: Int) {
        if (!CheckRootStatus.lastCheckResult && rootRequiredIds.contains(id)) {
            Toast.makeText(context, "Root permission not granted; this feature is unavailable.", Toast.LENGTH_SHORT).show()
            return
        }

        when (id) {
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
            R.id.nav_diagnostics -> {
                val intent = Intent(context, ActivityDiagnostics::class.java)
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
