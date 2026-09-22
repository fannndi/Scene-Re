package com.omarea.vtools.privilege

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omarea.common.shell.ShellModeProvider
import com.omarea.common.shell.ShellMode
import com.omarea.store.SpfConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the privilege tier state machine without requiring root or Shizuku:
 * persistence, shell mode routing and provider registration.
 */
@RunWith(AndroidJUnit4::class)
class PrivilegeManagerInstrumentedTest {
    private lateinit var context: Context
    private var originalTier: PrivilegeTier = PrivilegeTier.ROOT

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        originalTier = PrivilegeManager.tier
    }

    @After
    fun tearDown() {
        PrivilegeManager.setTier(context, originalTier)
    }

    @Test
    fun tierStorageRoundTrip() {
        assertEquals(PrivilegeTier.ROOT, PrivilegeTier.fromStorage("root"))
        assertEquals(PrivilegeTier.SHIZUKU, PrivilegeTier.fromStorage("shizuku"))
        assertEquals(PrivilegeTier.NON_ROOT, PrivilegeTier.fromStorage("non_root"))
        assertEquals(null, PrivilegeTier.fromStorage("unknown"))
    }

    @Test
    fun nonRootTierIsPersistedAndRouted() {
        PrivilegeManager.setTier(context, PrivilegeTier.NON_ROOT)

        val stored = context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
            .getString(SpfConfig.GLOBAL_SPF_PRIVILEGE_TIER, null)
        assertEquals("non_root", stored)
        assertEquals(PrivilegeTier.NON_ROOT, PrivilegeManager.tier)
        assertEquals(ShellMode.NON_ROOT, ShellModeProvider.mode)
        assertFalse(PrivilegeManager.isPrivileged)
        assertFalse(PrivilegeManager.hasRootAccess)
    }

    @Test
    fun shizukuShellProviderIsRegistered() {
        assertNotNull(ShellModeProvider.shizukuShellProvider)
        assertTrue(ShellModeProvider.shizukuShellProvider is PrivilegeManager)
    }

    @Test
    fun rootTierFallsBackWhenRootIsUnavailable() {
        PrivilegeManager.setTier(context, PrivilegeTier.ROOT)
        val effective = PrivilegeManager.effectiveTier
        if (PrivilegeManager.rootAvailable) {
            assertEquals(PrivilegeTier.ROOT, effective)
        } else {
            assertTrue(effective == PrivilegeTier.SHIZUKU || effective == PrivilegeTier.NON_ROOT)
        }
    }
}
