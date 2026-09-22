package com.omarea.vtools.debug

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Verifies the debug-only agent diagnostics used for USB-driven testing.
 * These tests never modify system state.
 */
@RunWith(AndroidJUnit4::class)
class AgentDebugInstrumentedTest {
    @Test
    fun snapshotIsValidJson() {
        val snapshot = AgentDebug.buildSnapshot(ApplicationProvider.getApplicationContext())
        val json = JSONObject(snapshot)

        assertTrue(json.has("app"))
        assertTrue(json.has("device"))
        assertTrue(json.has("capabilities"))

        val app = json.getJSONObject("app")
        assertEquals("com.omarea.vtools", app.getString("packageName"))
        assertTrue(app.getBoolean("debuggable"))
        assertTrue(app.getInt("versionCode") > 0)

        val device = json.getJSONObject("device")
        assertNotNull(device.getString("soc"))
        assertTrue(device.getInt("sdkInt") >= 24)
    }

    @Test
    fun snapshotIsWrittenToExternalFilesDir() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val snapshot = AgentDebug.buildSnapshot(context)

        val path = AgentDebug.writeSnapshot(context, snapshot)
        val file = File(path)
        assertTrue(file.exists())
        assertEquals(AgentDebug.SNAPSHOT_FILE_NAME, file.name)
        assertTrue(file.readText().contains("\"packageName\""))
    }

    @Test
    fun logcatDumpReturnsOutput() {
        val output = AgentDebug.dumpLogcat(80)
        assertNotNull(output)
        assertTrue(output.isNotEmpty())
    }
}
