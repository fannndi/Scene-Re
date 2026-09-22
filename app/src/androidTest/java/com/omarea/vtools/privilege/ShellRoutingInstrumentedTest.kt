package com.omarea.vtools.privilege

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.ShellMode
import com.omarea.common.shell.ShellModeProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * USB-friendly shell routing test: in NON_ROOT mode commands must run with the app uid,
 * so no `su` prompt is triggered and the output never reports uid 0.
 */
@RunWith(AndroidJUnit4::class)
class ShellRoutingInstrumentedTest {
    private var originalMode: ShellMode = ShellMode.ROOT

    @Before
    fun setUp() {
        originalMode = ShellModeProvider.mode
        KeepShellPublic.destroyAll()
        KeepShellPublic.tryExit()
        ShellModeProvider.mode = ShellMode.NON_ROOT
    }

    @After
    fun tearDown() {
        KeepShellPublic.destroyAll()
        KeepShellPublic.tryExit()
        ShellModeProvider.mode = originalMode
    }

    @Test
    fun nonRootModeDoesNotEscalate() {
        val uid = KeepShellPublic.doCmdSync("id -u").trim().lines().lastOrNull { it.isNotBlank() }?.trim() ?: ""
        assertNotEquals("0", uid)
    }

    @Test
    fun nonRootModeStillRunsCommands() {
        val marker = "scene-routing-" + System.currentTimeMillis()
        val output = KeepShellPublic.doCmdSync("echo $marker")
        assertEquals(marker, output.trim())
    }
}
