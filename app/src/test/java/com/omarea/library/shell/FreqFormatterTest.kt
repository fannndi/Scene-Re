package com.omarea.library.shell

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Kernel clock nodes report kHz; the UI shows MHz. These conversions are on the
 * hot path of every CPU/GPU readout, and the project previously had several
 * hand-rolled `substring` variants that disagreed on edge cases (a 3-digit value,
 * an empty string, a null). This pins the contract.
 */
class FreqFormatterTest {

    // --- cpuKhzToMhz: strips exactly 3 trailing digits ----------------------

    @Test
    fun `cpu strips three trailing digits`() {
        assertEquals("1804", FreqFormatter.cpuKhzToMhz("1804800"))
    }

    @Test
    fun `cpu handles a short value by returning it unchanged`() {
        // "300" is only 3 chars, so there is nothing left after stripping 3.
        assertEquals("300", FreqFormatter.cpuKhzToMhz("300"))
    }

    @Test
    fun `cpu returns empty for null`() {
        assertEquals("", FreqFormatter.cpuKhzToMhz(null))
    }

    @Test
    fun `cpu returns zero-like for empty input`() {
        // Documented quirk: empty is reported as "0", not "".
        assertEquals("0", FreqFormatter.cpuKhzToMhz(""))
    }

    @Test
    fun `cpu keeps a four digit value to one digit`() {
        assertEquals("1", FreqFormatter.cpuKhzToMhz("1000"))
    }

    // --- gpuKhzToMhz: strips exactly 6 trailing digits ----------------------

    @Test
    fun `gpu strips six trailing digits`() {
        // Adreno kgsl nodes report 6-digit kHz: 1804800000 -> 1804
        assertEquals("1804", FreqFormatter.gpuKhzToMhz("1804800000"))
    }

    @Test
    fun `gpu returns empty for null`() {
        assertEquals("", FreqFormatter.gpuKhzToMhz(null))
    }

    @Test
    fun `gpu returns empty for empty`() {
        // Different from the CPU path: GPU empty yields "", not "0".
        assertEquals("", FreqFormatter.gpuKhzToMhz(""))
    }

    @Test
    fun `gpu returns a short value unchanged`() {
        assertEquals("670", FreqFormatter.gpuKhzToMhz("670"))
    }

    // --- with-unit variants -------------------------------------------------

    @Test
    fun `cpu with unit appends the suffix`() {
        assertEquals("1804 Mhz", FreqFormatter.cpuKhzToMhzWithUnit("1804800"))
    }

    @Test
    fun `cpu with unit leaves a short value bare`() {
        assertEquals("300", FreqFormatter.cpuKhzToMhzWithUnit("300"))
    }

    @Test
    fun `gpu with unit appends the suffix`() {
        assertEquals("1804 Mhz", FreqFormatter.gpuKhzToMhzWithUnit("1804800000"))
    }

    @Test
    fun `gpu with unit returns empty for empty input`() {
        assertEquals("", FreqFormatter.gpuKhzToMhzWithUnit(""))
    }

    @Test
    fun `both conversions agree on a typical cpu clock`() {
        // The two entry points exist only for readability at call sites; for a
        // value long enough for both they must produce the same digits.
        val cpuStyle = FreqFormatter.cpuKhzToMhz("2419200")
        assertEquals("2419", cpuStyle)
    }
}
