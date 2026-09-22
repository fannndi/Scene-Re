package com.omarea.library.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Security tests for [FrameworkControlValidation].
 *
 * Every value validated here ends up in a shell command that may run as root or shell uid, so the
 * interesting cases are the hostile ones. These tests assert that command-injection attempts and
 * option-injection attempts are rejected.
 */
class FrameworkControlValidationTest {

    @Test
    fun `accepts real appops operation names`() {
        assertTrue(FrameworkControlValidation.isValidOpName("RUN_IN_BACKGROUND"))
        assertTrue(FrameworkControlValidation.isValidOpName("RUN_ANY_IN_BACKGROUND"))
        assertTrue(FrameworkControlValidation.isValidOpName("WAKE_LOCK"))
        assertTrue(FrameworkControlValidation.isValidOpName("POST_NOTIFICATION"))
        assertTrue(FrameworkControlValidation.isValidOpName("SYSTEM_ALERT_WINDOW"))
    }

    @Test
    fun `rejects appops names that could inject shell syntax`() {
        assertFalse(FrameworkControlValidation.isValidOpName("RUN_IN_BACKGROUND; rm -rf /"))
        assertFalse(FrameworkControlValidation.isValidOpName("RUN_IN_BACKGROUND\nid"))
        assertFalse(FrameworkControlValidation.isValidOpName("RUN_IN_BACKGROUND`id`"))
        assertFalse(FrameworkControlValidation.isValidOpName("RUN_IN_BACKGROUND\$(id)"))
        assertFalse(FrameworkControlValidation.isValidOpName("RUN_IN_BACKGROUND && reboot"))
        assertFalse(FrameworkControlValidation.isValidOpName("RUN_IN_BACKGROUND|sh"))
        assertFalse(FrameworkControlValidation.isValidOpName("\"RUN_IN_BACKGROUND\""))
        assertFalse(FrameworkControlValidation.isValidOpName("RUN IN BACKGROUND"))
    }

    @Test
    fun `rejects lowercase and malformed appops names`() {
        assertFalse(FrameworkControlValidation.isValidOpName("run_in_background"))
        assertFalse(FrameworkControlValidation.isValidOpName("_RUN_IN_BACKGROUND"))
        assertFalse(FrameworkControlValidation.isValidOpName("1RUN_IN_BACKGROUND"))
        assertFalse(FrameworkControlValidation.isValidOpName("RU"))
        assertFalse(FrameworkControlValidation.isValidOpName(""))
        assertFalse(FrameworkControlValidation.isValidOpName(null))
    }

    @Test
    fun `accepts only the documented appops modes`() {
        for (mode in listOf("allow", "ignore", "deny", "default", "foreground")) {
            assertTrue("expected '$mode' to be valid", FrameworkControlValidation.isValidOpMode(mode))
        }
    }

    @Test
    fun `rejects appops modes outside the allowed set`() {
        assertFalse(FrameworkControlValidation.isValidOpMode("allow; id"))
        assertFalse(FrameworkControlValidation.isValidOpMode("ALLOW"))
        assertFalse(FrameworkControlValidation.isValidOpMode("allow --user 0"))
        assertFalse(FrameworkControlValidation.isValidOpMode(""))
        assertFalse(FrameworkControlValidation.isValidOpMode(null))
    }

    @Test
    fun `accepts real runtime permission names`() {
        assertTrue(FrameworkControlValidation.isValidPermissionName("android.permission.CAMERA"))
        assertTrue(FrameworkControlValidation.isValidPermissionName("android.permission.ACCESS_FINE_LOCATION"))
        assertTrue(FrameworkControlValidation.isValidPermissionName("android.permission.POST_NOTIFICATIONS"))
        assertTrue(FrameworkControlValidation.isValidPermissionName("android.permission.READ_EXTERNAL_STORAGE"))
    }

    @Test
    fun `rejects permissions outside the android namespace`() {
        // Without the namespace check, an attacker could pass a flag as a "permission".
        assertFalse(FrameworkControlValidation.isValidPermissionName("--user"))
        assertFalse(FrameworkControlValidation.isValidPermissionName("-g"))
        assertFalse(FrameworkControlValidation.isValidPermissionName("com.example.permission.FOO"))
        assertFalse(FrameworkControlValidation.isValidPermissionName("CAMERA"))
    }

    @Test
    fun `rejects permission names that could inject shell syntax`() {
        assertFalse(FrameworkControlValidation.isValidPermissionName("android.permission.CAMERA; id"))
        assertFalse(FrameworkControlValidation.isValidPermissionName("android.permission.CAMERA && reboot"))
        assertFalse(FrameworkControlValidation.isValidPermissionName("android.permission.CAMERA\$(id)"))
        assertFalse(FrameworkControlValidation.isValidPermissionName("android.permission.CAMERA`id`"))
        assertFalse(FrameworkControlValidation.isValidPermissionName("android.permission.CAMERA\nid"))
        assertFalse(FrameworkControlValidation.isValidPermissionName("android.permission.CAM ERA"))
        assertFalse(FrameworkControlValidation.isValidPermissionName("android.permission.CAMERA'"))
    }

    @Test
    fun `rejects empty and null permissions`() {
        assertFalse(FrameworkControlValidation.isValidPermissionName(""))
        assertFalse(FrameworkControlValidation.isValidPermissionName(null))
        assertFalse(FrameworkControlValidation.isValidPermissionName("android.permission."))
    }

    @Test
    fun `accepts non-negative uids only`() {
        assertTrue(FrameworkControlValidation.isValidUid(0))
        assertTrue(FrameworkControlValidation.isValidUid(10123))
        assertFalse(FrameworkControlValidation.isValidUid(-1))
    }

    @Test
    fun `accepts only safe user id arguments`() {
        assertTrue(FrameworkControlValidation.isValidUserId("0"))
        assertTrue(FrameworkControlValidation.isValidUserId("10"))
        assertTrue(FrameworkControlValidation.isValidUserId("current"))
        assertTrue(FrameworkControlValidation.isValidUserId("all"))
    }

    @Test
    fun `rejects user id arguments that could inject options`() {
        assertFalse(FrameworkControlValidation.isValidUserId("0; id"))
        assertFalse(FrameworkControlValidation.isValidUserId("--user 0"))
        assertFalse(FrameworkControlValidation.isValidUserId("-1"))
        assertFalse(FrameworkControlValidation.isValidUserId("0 1"))
        assertFalse(FrameworkControlValidation.isValidUserId(""))
        assertFalse(FrameworkControlValidation.isValidUserId(null))
    }

    @Test
    fun `accepts real package names`() {
        assertTrue(FrameworkControlValidation.isValidPackageName("com.omarea.vtools"))
        assertTrue(FrameworkControlValidation.isValidPackageName("com.android.systemui"))
        assertTrue(FrameworkControlValidation.isValidPackageName("com.miui.home"))
        assertTrue(FrameworkControlValidation.isValidPackageName("a.b"))
    }

    @Test
    fun `rejects package names that are not identifiers`() {
        // A single segment cannot be a package, and option-like values must never pass.
        assertFalse(FrameworkControlValidation.isValidPackageName("singlesegment"))
        assertFalse(FrameworkControlValidation.isValidPackageName("--user"))
        assertFalse(FrameworkControlValidation.isValidPackageName("com..vtools"))
        assertFalse(FrameworkControlValidation.isValidPackageName("com.vtools."))
        assertFalse(FrameworkControlValidation.isValidPackageName(".com.vtools"))
        assertFalse(FrameworkControlValidation.isValidPackageName("com.vt ools"))
        assertFalse(FrameworkControlValidation.isValidPackageName("com.vtools;id"))
        assertFalse(FrameworkControlValidation.isValidPackageName("com.vtools`id`"))
        assertFalse(FrameworkControlValidation.isValidPackageName("com.9vtools"))
        assertFalse(FrameworkControlValidation.isValidPackageName(""))
        assertFalse(FrameworkControlValidation.isValidPackageName(null))
    }

    @Test
    fun `maps the numeric standby buckets the framework reports`() {
        // `am get-standby-bucket` prints numbers, not names: active is 10 and rare is 40 on the
        // target device. Reading used to only accept names, so every read returned null and every
        // verified write looked like a failure.
        assertEquals(
            FrameworkAppControl.StandbyBucket.ACTIVE,
            FrameworkAppControl.StandbyBucket.fromId("10")
        )
        assertEquals(
            FrameworkAppControl.StandbyBucket.RARE,
            FrameworkAppControl.StandbyBucket.fromId("40")
        )
        assertEquals(
            FrameworkAppControl.StandbyBucket.RESTRICTED,
            FrameworkAppControl.StandbyBucket.fromId("45")
        )
    }

    @Test
    fun `still accepts standby bucket names and rejects unknown values`() {
        assertEquals(
            FrameworkAppControl.StandbyBucket.WORKING_SET,
            FrameworkAppControl.StandbyBucket.fromId("working_set")
        )
        assertEquals(
            FrameworkAppControl.StandbyBucket.ACTIVE,
            FrameworkAppControl.StandbyBucket.fromId("  ACTIVE  ")
        )
        assertNull(FrameworkAppControl.StandbyBucket.fromId("99"))
        assertNull(FrameworkAppControl.StandbyBucket.fromId("not a bucket"))
        assertNull(FrameworkAppControl.StandbyBucket.fromId(""))
        assertNull(FrameworkAppControl.StandbyBucket.fromId(null))
    }

    @Test
    fun `set-standby-bucket writes the name and reads back the number`() {
        // The two directions use different vocabularies; this asserts the write side uses names.
        assertEquals("active", FrameworkAppControl.StandbyBucket.ACTIVE.id)
        assertEquals("restricted", FrameworkAppControl.StandbyBucket.RESTRICTED.id)
        assertEquals(
            10,
            FrameworkAppControl.StandbyBucket.fromId(
                FrameworkAppControl.StandbyBucket.ACTIVE.frameworkValue.toString()
            )!!.frameworkValue
        )
    }
}
