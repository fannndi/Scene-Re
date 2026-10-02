package com.omarea.ui.screen

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.omarea.common.ui.ThemeMode
import com.omarea.util.CheckRootStatus
import com.omarea.util.RootState
import com.omarea.vtools.R
import com.omarea.ui.activity.*
import com.omarea.vtools.databinding.FragmentNavBinding
import com.omarea.ui.overview.OverviewMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.omarea.ui.theme.SceneTheme

class FragmentNav : Fragment() {
    private lateinit var themeMode: ThemeMode
    private var _binding: FragmentNavBinding? = null
    private val binding get() = _binding!!
    private val rootRequiredIds = setOf(
        R.id.nav_processes,
        R.id.nav_fps_chart,
        R.id.nav_benchmark,
        R.id.nav_charge,
        R.id.nav_power_utilization,
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
            // Recomposes the moment an async root check completes — previously
            // this read a plain Boolean once, leaving root menus permanently dead.
            val isRootAvailable by CheckRootStatus.rootStatus.collectAsState()
            val controller = SceneTheme.controller(themeMode.isDarkMode)
            MiuixTheme(controller = controller) {
                OverviewMenu(
                    isRootAvailable = isRootAvailable,
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
        // Re-verify root on return (e.g. granted in the Magisk/APatch manager).
        if (!CheckRootStatus.lastCheckResult) {
            CheckRootStatus.checkRootAsync()
        }
    }

    private fun handleNavClick(id: Int) {
        if (!CheckRootStatus.lastCheckResult && rootRequiredIds.contains(id)) {
            requestRootThen(id)
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
            R.id.nav_benchmark -> {
                val intent = Intent(context, ActivityBenchmark::class.java)
                startActivity(intent)
                return
            }
            R.id.nav_diagnostics -> {
                val intent = Intent(context, ActivityDiagnostics::class.java)
                startActivity(intent)
                return
            }
            R.id.nav_additional_all -> {
                startActivity(Intent(context, ActivityTweaks::class.java))
                return
            }
            else -> {}
        }
    }


    /**
     * Root missing: run the standard grant-root flow (su prompt, then the
     * "root rejected" retry dialog) and open [id] on success. Replaces the old
     * dead-end toast — the menu item itself is no longer clickable-blocked.
     */
    private fun requestRootThen(id: Int) {
        val act = activity ?: return
        // Definitive "root hilang": retrying su is pointless — explain instead.
        if (CheckRootStatus.currentRootState() == RootState.MISSING) {
            Toast.makeText(context, getString(R.string.toast_root_missing), Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(context, getString(R.string.not_root_disabled), Toast.LENGTH_SHORT).show()
        CheckRootStatus(act, {
            handleNavClick(id)
        }, false, null).forceGetRoot()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
