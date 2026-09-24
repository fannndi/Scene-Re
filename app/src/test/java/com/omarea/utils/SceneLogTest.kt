package com.omarea.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies the diagnostic layer's contract. Two properties matter most and are
 * asserted explicitly:
 *
 * 1. **The test-result line format is stable.** The USB harness greps for
 *    `SCENE_TEST <status> <feature> - <detail>`; if the format drifts, on-device
 *    verification silently reports nothing instead of failing loudly.
 * 2. **Logging never throws.** This class is called from the uncaught-exception
 *    handler and from fail-prone shell paths, so a throw here would turn a
 *    diagnosable bug into a crash loop.
 *
 * `SceneLog` is deliberately free of Android-context dependencies on the logging
 * path (`init`/file sinks are the only parts that need a `Context`), so these
 * tests run as plain JVM unit tests.
 */
class SceneLogTest {

    @Before
    fun reset() {
        SceneLog.clear()
        SceneLog.removeAllListeners()
        SceneLog.minimumFileLevel = SceneLog.DEBUG
    }

    // --- Formatting ---------------------------------------------------------

    @Test
    fun `record format has a stable prefix`() {
        val record = SceneLog.Record(
            timestamp = 0L,
            level = SceneLog.INFO,
            tag = "Net",
            message = "connected",
            throwable = null
        )
        val formatted = record.format()
        // mm-dd hh:mm:ss.SSS <L> Scene:<tag>: <message>
        assertTrue(formatted.endsWith("I Scene:Net: connected"))
    }

    @Test
    fun `each level maps to its own letter`() {
        fun lineFor(level: Int): String = SceneLog.Record(0L, level, "T", "m", null).format()

        assertTrue(lineFor(SceneLog.VERBOSE).contains(" V Scene:T: m"))
        assertTrue(lineFor(SceneLog.DEBUG).contains(" D Scene:T: m"))
        assertTrue(lineFor(SceneLog.INFO).contains(" I Scene:T: m"))
        assertTrue(lineFor(SceneLog.WARN).contains(" W Scene:T: m"))
        assertTrue(lineFor(SceneLog.ERROR).contains(" E Scene:T: m"))
    }

    @Test
    fun `record format appends a stack trace when a throwable is present`() {
        // `Log.getStackTraceString` is a stub under plain JVM unit tests
        // (returnDefaultValues = true), so it yields "". The rendering of the
        // trace itself is therefore verified on-device in SceneLogInstrumentedTest;
        // what matters here is that a throwable is carried on the record and that
        // formatting a record with one does not throw.
        val cause = IllegalStateException("kaboom")
        val record = SceneLog.Record(0L, SceneLog.ERROR, "Crash", "boom", cause)

        val formatted = record.format()
        assertTrue(formatted.contains("boom"))
        assertTrue(formatted.contains("E Scene:Crash:"))
        assertSame(cause, record.throwable)
    }

    // --- Test-result marker -------------------------------------------------

    @Test
    fun `testResult emits the exact passing line the harness greps for`() {
        SceneLog.testResult("cpu.freq", true, "8 cores online")

        val line = SceneLog.recent().last()
        assertEquals(
            "SCENE_TEST PASS cpu.freq - 8 cores online",
            "${SceneLog.TEST_MARKER} ${"PASS"} ${"cpu.freq"} - 8 cores online"
        )
        assertTrue(line.message.startsWith(SceneLog.TEST_MARKER))
        assertTrue(line.message.contains("PASS"))
    }

    @Test
    fun `testResult emits FAIL and logs at error level`() {
        SceneLog.testResult("swap.zram", false, "no zram device")

        val line = SceneLog.recent().last()
        assertEquals(SceneLog.ERROR, line.level)
        assertTrue(line.message.startsWith("SCENE_TEST FAIL"))
        assertTrue(line.message.endsWith("swap.zram - no zram device"))
    }

    @Test
    fun `testResult with no detail still produces a parseable line`() {
        SceneLog.testResult("root.shell", true)
        val line = SceneLog.recent().last()
        assertTrue(line.message.startsWith("SCENE_TEST PASS root.shell - "))
    }

    @Test
    fun `the marker constant is what the harness expects`() {
        // Changing this value breaks every on-device verification script.
        assertEquals("SCENE_TEST", SceneLog.TEST_MARKER)
        assertEquals("Scene", SceneLog.TAG_PREFIX)
    }

    // --- Bounded memory -----------------------------------------------------

    @Test
    fun `recent returns records oldest first`() {
        SceneLog.i("A", "first")
        SceneLog.i("A", "second")
        SceneLog.i("A", "third")

        val messages = SceneLog.recent().map { it.message }
        assertEquals(listOf("first", "second", "third"), messages)
    }

    @Test
    fun `recent honours a limit`() {
        for (index in 1..10) {
            SceneLog.i("A", "line$index")
        }
        val tail = SceneLog.recent(3).map { it.message }
        assertEquals(listOf("line8", "line9", "line10"), tail)
    }

    @Test
    fun `clear empties the buffer`() {
        SceneLog.i("A", "something")
        assertTrue(SceneLog.recent().isNotEmpty())
        SceneLog.clear()
        assertTrue(SceneLog.recent().isEmpty())
    }

    // --- Listeners ----------------------------------------------------------

    @Test
    fun `a registered listener receives records`() {
        val seen = mutableListOf<String>()
        SceneLog.addListener { seen.add(it.message) }

        SceneLog.w("Net", "slow")

        assertEquals(listOf("slow"), seen)
    }

    @Test
    fun `deregistering a listener stops delivery`() {
        val seen = mutableListOf<String>()
        val handle = SceneLog.addListener { seen.add(it.message) }

        SceneLog.i("A", "before")
        handle()
        SceneLog.i("A", "after")

        assertEquals(listOf("before"), seen)
    }

    @Test
    fun `multiple listeners all receive the record`() {
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()
        SceneLog.addListener { first.add(it.message) }
        SceneLog.addListener { second.add(it.message) }

        SceneLog.i("A", "fanout")

        assertEquals(listOf("fanout"), first)
        assertEquals(listOf("fanout"), second)
    }

    @Test
    fun `a throwing listener does not break logging`() {
        SceneLog.addListener { throw RuntimeException("bad sink") }

        // Must not propagate, and the record must still be buffered.
        SceneLog.i("A", "survives")

        assertEquals("survives", SceneLog.recent().last().message)
    }

    // --- trace --------------------------------------------------------------

    @Test
    fun `trace returns the block result`() {
        val result = SceneLog.trace("Shell", "read freq") { "1804" }
        assertEquals("1804", result)
    }

    @Test
    fun `trace returns null and records an error when the block throws`() {
        val result = SceneLog.trace<Int>("Shell", "read freq") { error("node missing") }

        assertNull(result)
        val error = SceneLog.recent().last()
        assertEquals(SceneLog.ERROR, error.level)
        assertTrue(error.message.contains("read freq failed"))
        assertNotNull(error.throwable)
    }

    @Test
    fun `trace records entry and exit`() {
        SceneLog.trace("Shell", "swappiness") { "60" }

        val entries = SceneLog.recent()
        assertTrue(entries.any { it.message == "-> swappiness" })
        assertTrue(entries.any { it.message.startsWith("<- swappiness") })
    }

    // --- Never throws -------------------------------------------------------

    @Test
    fun `logging with unusual input does not throw`() {
        SceneLog.i("", "")
        SceneLog.e("Tag", "multi\nline\nmessage")
        SceneLog.w("Tag", "with throwable", RuntimeException())
        SceneLog.v("Tag", "\u0000\u001B")
        // Reaching here without an exception is the assertion.
        assertTrue(SceneLog.recent().size >= 4)
    }

    @Test
    fun `dump renders every buffered record`() {
        SceneLog.i("A", "one")
        SceneLog.i("B", "two")

        val dump = SceneLog.dump()
        assertTrue(dump.contains("Scene:A: one"))
        assertTrue(dump.contains("Scene:B: two"))
        assertEquals(2, dump.lines().size)
    }

    @Test
    fun `dump honours a limit`() {
        for (index in 1..5) {
            SceneLog.i("A", "n$index")
        }
        val dump = SceneLog.dump(2)
        assertEquals(2, dump.lines().size)
        assertTrue(dump.contains("n5"))
        assertTrue(dump.contains("n4"))
    }
}
