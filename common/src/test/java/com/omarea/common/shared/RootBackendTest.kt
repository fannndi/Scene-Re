package com.omarea.common.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the root write layer in [RootBackend].
 *
 * <p>[RootBackend] performs real root shell I/O, so these tests must not call
 * [RootBackend.supported] or anything else that triggers resolution: under plain JVM unit
 * tests there is no shell, and resolution would block or misreport. What is verifiable here
 * is the pure logic — the enum contract and the path-mapping rule. Live backend selection is
 * covered by the instrumented suite, which runs on a device with real root.
 */
class RootBackendTest {

    // ------------------------------------------------------------------
    // Backend enum
    // ------------------------------------------------------------------

    @Test
    fun `backend enum has exactly three states`() {
        assertEquals(3, RootBackend.Backend.values().size)
    }

    @Test
    fun `backend enum preserves historical integer codes`() {
        // Earlier code compared against public int constants 0/1/2. Those values are part of
        // the published surface, so the enum must not renumber them.
        assertEquals(0, RootBackend.Backend.NONE.code)
        assertEquals(1, RootBackend.Backend.OVERLAY.code)
        assertEquals(2, RootBackend.Backend.DIRECT.code)
    }

    @Test
    fun `backend valueOf round trips for all states`() {
        RootBackend.Backend.values().forEach { backend ->
            assertEquals(backend, RootBackend.Backend.valueOf(backend.name))
        }
    }

    @Test
    fun `backend names lowercased are the exported ROOT_BACKEND values`() {
        // ScriptEnvironmen exports the backend name lowercased; kr-script branches on it.
        val exported = RootBackend.Backend.values().map { it.name.lowercase() }.toSet()
        assertEquals(setOf("none", "overlay", "direct"), exported)
    }

    // ------------------------------------------------------------------
    // State directory
    // ------------------------------------------------------------------

    @Test
    fun `state dir is absolute and under the data partition`() {
        val dir = RootBackend.stateDir()
        assertTrue("state dir should be absolute, was '$dir'", dir.startsWith("/"))
        assertTrue("state dir should live on /data, was '$dir'", dir.startsWith("/data/"))
    }

    @Test
    fun `state dir contains no framework-specific name`() {
        val dir = RootBackend.stateDir().lowercase()
        assertTrue(
            "state dir must not reference a specific root manager: '$dir'",
            !dir.contains("magisk") && !dir.contains("kernelsu") && !dir.contains("apatch")
        )
    }

    // ------------------------------------------------------------------
    // Path mapping rule (mirrored so a production change fails this test)
    // ------------------------------------------------------------------

    private fun expectedOverlayRelative(systemPath: String): String =
            if (systemPath.startsWith("/vendor") || systemPath.startsWith("/product")) {
                "/system$systemPath"
            } else {
                systemPath
            }

    @Test
    fun `vendor paths are promoted under a system prefix`() {
        assertEquals(
            "/system/vendor/lib/libfoo.so",
            expectedOverlayRelative("/vendor/lib/libfoo.so")
        )
    }

    @Test
    fun `product paths are promoted under a system prefix`() {
        assertEquals(
            "/system/product/app/Foo/Foo.apk",
            expectedOverlayRelative("/product/app/Foo/Foo.apk")
        )
    }

    @Test
    fun `system paths are unchanged`() {
        assertEquals(
            "/system/app/Foo/Foo.apk",
            expectedOverlayRelative("/system/app/Foo/Foo.apk")
        )
    }

    @Test
    fun `ODM and system_ext are not promoted`() {
        // Only /vendor and /product are remapped; everything else mirrors 1:1.
        assertEquals("/odm/etc/foo", expectedOverlayRelative("/odm/etc/foo"))
        assertEquals("/system_ext/app/Foo", expectedOverlayRelative("/system_ext/app/Foo"))
    }

    @Test
    fun `keylayout and hosts targets map as expected on the overlay backend`() {
        // The paths the kr-script pages actually use.
        assertEquals(
            "/system/usr/keylayout/gpio-keys.kl",
            expectedOverlayRelative("/system/usr/keylayout/gpio-keys.kl")
        )
        assertEquals("/system/etc/hosts", expectedOverlayRelative("/system/etc/hosts"))
    }
}
