package com.omarea.common.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ShellEscape] is the project's only defence against a value escaping its
 * argument and becoming a second shell command. Most call sites build commands
 * by string interpolation, and the values include package names, config-file
 * paths and kr-script parameters — all of which can be influenced from outside
 * the app. A regression here is a privilege-escalation bug on a rooted device.
 */
class ShellEscapeTest {

    // --- quote --------------------------------------------------------------

    @Test
    fun `quote wraps a plain value in single quotes`() {
        assertEquals("'hello'", ShellEscape.quote("hello"))
    }

    @Test
    fun `quote preserves embedded spaces`() {
        assertEquals("'a b c'", ShellEscape.quote("a b c"))
    }

    @Test
    fun `quote escapes an embedded single quote`() {
        // 'it'\''s' closes the quote, emits a literal quote, and reopens.
        assertEquals("'it'\\''s'", ShellEscape.quote("it's"))
    }

    @Test
    fun `quote neutralises a command substitution attempt`() {
        // Inside single quotes a $() is literal, so this must NOT be able to run.
        val quoted = ShellEscape.quote("\$(reboot)")
        assertEquals("'\$(reboot)'", quoted)
        assertTrue(quoted.startsWith("'"))
        assertTrue(quoted.endsWith("'"))
    }

    @Test
    fun `quote neutralises a semicolon chain`() {
        val quoted = ShellEscape.quote("a; rm -rf /")
        // The semicolon stays inside the quoted region, so it never splits.
        assertEquals("'a; rm -rf /'", quoted)
    }

    @Test
    fun `quote of an empty string yields empty quotes`() {
        assertEquals("''", ShellEscape.quote(""))
    }

    // --- isSafePath ---------------------------------------------------------

    @Test
    fun `a sysfs path is safe`() {
        assertTrue(ShellEscape.isSafePath("/sys/kernel/fpsgo/fstb/fpsgo_status"))
    }

    @Test
    fun `a proc path is safe`() {
        assertTrue(ShellEscape.isSafePath("/proc/sys/vm/swappiness"))
    }

    @Test
    fun `a property name is safe`() {
        assertTrue(ShellEscape.isSafePath("ro.board.platform"))
    }

    @Test
    fun `a persist property name is safe`() {
        assertTrue(ShellEscape.isSafePath("persist.sys.locale"))
    }

    @Test
    fun `an empty path is rejected`() {
        assertFalse(ShellEscape.isSafePath(""))
    }

    @Test
    fun `a path containing a newline is rejected`() {
        // This is the injection case: quoting alone would keep it as one
        // argument, but the *value* is still a different path than intended.
        assertFalse(ShellEscape.isSafePath("/sys/x\nreboot"))
    }

    @Test
    fun `a path containing a carriage return is rejected`() {
        assertFalse(ShellEscape.isSafePath("/sys/x\rreboot"))
    }

    @Test
    fun `a path containing a null byte is rejected`() {
        assertFalse(ShellEscape.isSafePath("/sys/x\u0000reboot"))
    }

    @Test
    fun `a path containing a space is rejected`() {
        assertFalse(ShellEscape.isSafePath("/sys/x y"))
    }

    @Test
    fun `a path containing a dollar sign is rejected`() {
        assertFalse(ShellEscape.isSafePath("/sys/\$(reboot)"))
    }

    @Test
    fun `an over-long path is rejected`() {
        assertFalse(ShellEscape.isSafePath("/" + "a".repeat(5000)))
    }

    @Test
    fun `a relative dotted property with a slash is rejected`() {
        assertFalse(ShellEscape.isSafePath("foo/bar"))
    }

    // --- cmd ----------------------------------------------------------------

    @Test
    fun `cmd with no arguments returns the bare program`() {
        assertEquals("cat", ShellEscape.cmd("cat"))
    }

    @Test
    fun `cmd quotes every argument`() {
        assertEquals(
            "cat '/sys/kernel/fpsgo/fstb/fpsgo_status'",
            ShellEscape.cmd("cat", "/sys/kernel/fpsgo/fstb/fpsgo_status")
        )
    }

    @Test
    fun `cmd quotes each of multiple arguments separately`() {
        assertEquals("pm 'suspend' 'com.foo.bar'", ShellEscape.cmd("pm", "suspend", "com.foo.bar"))
    }

    @Test
    fun `cmd does not quote the program name`() {
        // The program is expected to be a source literal, never a runtime value.
        assertTrue(ShellEscape.cmd("my-path/tool", "arg").startsWith("my-path/tool "))
    }

    @Test
    fun `cmd neutralises an argument that tries to chain`() {
        val out = ShellEscape.cmd("echo", "a; reboot")
        assertEquals("echo 'a; reboot'", out)
        // The semicolon is inside quotes, so the shell sees one argument.
        assertFalse(out.contains("; reboot' ") && !out.contains("'a; reboot'"))
    }

    // --- cmdLine ------------------------------------------------------------

    @Test
    fun `cmdLine behaves like cmd for clean args`() {
        assertEquals(
            "pm 'suspend' 'com.foo.bar'",
            ShellEscape.cmdLine("pm", "suspend", "com.foo.bar")
        )
    }

    @Test
    fun `cmdLine substitutes an inert placeholder for a multiline value`() {
        // A newline is never legitimate in an identifier, so instead of quoting
        // it we replace the whole argument, making the failure visible.
        assertEquals("pm 'suspend' ''", ShellEscape.cmdLine("pm", "suspend", "a\nb"))
    }

    @Test
    fun `cmdLine rejects a tab`() {
        assertEquals("echo ''", ShellEscape.cmdLine("echo", "a\tb"))
    }

    @Test
    fun `cmdLine rejects a DEL character`() {
        assertEquals("echo ''", ShellEscape.cmdLine("echo", "a\u007Fb"))
    }

    @Test
    fun `cmdLine with no arguments returns the bare program`() {
        assertEquals("getprop", ShellEscape.cmdLine("getprop"))
    }

    // --- isSingleLine -------------------------------------------------------

    @Test
    fun `a normal identifier is single line`() {
        assertTrue(ShellEscape.isSingleLine("com.example.app"))
    }

    @Test
    fun `a space is still single line`() {
        // Spaces are legitimate inside a quoted argument.
        assertTrue(ShellEscape.isSingleLine("a b"))
    }

    @Test
    fun `a newline is not single line`() {
        assertFalse(ShellEscape.isSingleLine("a\nb"))
    }

    @Test
    fun `a carriage return is not single line`() {
        assertFalse(ShellEscape.isSingleLine("a\rb"))
    }

    @Test
    fun `a tab is not single line`() {
        assertFalse(ShellEscape.isSingleLine("a\tb"))
    }

    @Test
    fun `a null is not single line`() {
        assertFalse(ShellEscape.isSingleLine("a\u0000b"))
    }

    @Test
    fun `an escape character is not single line`() {
        assertFalse(ShellEscape.isSingleLine("a\u001Bb"))
    }
}
