package com.omarea.vtools.activities

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.omarea.permissions.CheckRootStatus
import com.omarea.store.SpfConfig
import com.omarea.ui.TabIconHelper2
import com.omarea.utils.ElectricityUnit
import com.omarea.vtools.R
import com.omarea.vtools.fragments.FragmentCpuModes
import com.omarea.vtools.fragments.FragmentHome
import com.omarea.vtools.fragments.FragmentNav
import com.omarea.vtools.fragments.FragmentNotRoot
import com.omarea.vtools.databinding.ActivityMainBinding
import java.util.ArrayDeque

class ActivityMain : ActivityBase() {
    companion object {
        const val EXTRA_SELECT_TAB = "select_tab"
        const val TAB_NAV = 0
        const val TAB_HOME = 1
        const val TAB_TUNER = 2
        var lastSelectedTab = TAB_HOME
    }

    private lateinit var globalSPF: SharedPreferences
    private lateinit var binding: ActivityMainBinding
    private val tabHistory = ArrayDeque<Int>()
    private var suppressTabHistory = false

    @SuppressLint("ResourceAsColor")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!ActivityStartSplash.finished) {
            val intent = Intent(this.applicationContext, ActivityStartSplash::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            // intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            startActivity(intent)
            finish()
            return
        }

        globalSPF = getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
        if (!globalSPF.contains(SpfConfig.GLOBAL_SPF_CURRENT_NOW_UNIT)) {
            globalSPF.edit().putInt(SpfConfig.GLOBAL_SPF_CURRENT_NOW_UNIT, ElectricityUnit().getDefaultElectricityUnit(this)).apply()
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        ViewCompat.setOnApplyWindowInsetsListener(binding.tabBar) { view, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(view.paddingLeft, topInset, view.paddingRight, view.paddingBottom)
            insets
        }

        val tabIconHelper2 = TabIconHelper2(binding.tabList, binding.tabContent, this, R.layout.list_item_tab2)
        tabIconHelper2.newTabSpec(getString(R.string.app_nav), getDrawable(R.drawable.app_menu)!!, FragmentNav.createPage(themeMode))
        tabIconHelper2.newTabSpec(getString(R.string.app_home), getDrawable(R.drawable.app_home)!!, (if (CheckRootStatus.lastCheckResult) {
            FragmentHome()
        } else {
            FragmentNotRoot()
        }))
        tabIconHelper2.newTabSpec(getString(R.string.app_tuner), getDrawable(R.drawable.app_settings)!!, FragmentCpuModes())
        binding.tabContent.adapter = tabIconHelper2.adapter
        binding.tabList.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                if (suppressTabHistory) {
                    return
                }
                val position = tab?.position ?: return
                if (tabHistory.peekLast() != position) {
                    tabHistory.addLast(position)
                }
                lastSelectedTab = position
            }

            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })
        setInitialTab(intent?.getIntExtra(EXTRA_SELECT_TAB, TAB_HOME) ?: TAB_HOME)

        binding.actionSettings.setOnClickListener {
            startActivity(Intent(this.applicationContext, ActivityOtherSettings::class.java))
        }
    }

    override fun onResume() {
        super.onResume()

        // In-app update checks were removed in Scene-Re (offline build).
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setInitialTab(intent?.getIntExtra(EXTRA_SELECT_TAB, TAB_HOME) ?: TAB_HOME)
    }

    private fun setInitialTab(index: Int) {
        if (!::binding.isInitialized) {
            return
        }
        val tabCount = binding.tabList.tabCount
        val tabIndex = index.coerceIn(0, (tabCount - 1).coerceAtLeast(0))
        suppressTabHistory = true
        binding.tabList.getTabAt(tabIndex)?.select()
        suppressTabHistory = false
        tabHistory.clear()
        tabHistory.addLast(tabIndex)
        lastSelectedTab = tabIndex
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
    }

    //返回键事件
    override fun onBackPressed() {
        try {
            when {
                supportFragmentManager.backStackEntryCount > 0 -> {
                    supportFragmentManager.popBackStack()
                }
                tabHistory.size > 1 -> {
                    tabHistory.removeLast()
                    val previous = tabHistory.peekLast()
                    if (previous != null) {
                        suppressTabHistory = true
                        binding.tabList.getTabAt(previous)?.select()
                        suppressTabHistory = false
                        return
                    }
                    excludeFromRecent()
                    super.onBackPressed()
                }
                else -> {
                    excludeFromRecent()
                    super.onBackPressed()
                }
            }
        } catch (ex: Exception) {
            ex.stackTrace
        }
    }

    public override fun onPause() {
        super.onPause()
        if (!CheckRootStatus.lastCheckResult) {
            finish()
        }
    }

    override fun onDestroy() {
        val fragmentManager = supportFragmentManager
        fragmentManager.fragments.clear()
        super.onDestroy()
    }
}
