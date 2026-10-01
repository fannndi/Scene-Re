package com.omarea.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeStockRestorerTest {

    @Test
    fun `command only writes charge nodes behind guards`() {
        val cmd = ChargeStockRestorer.command()
        // Both write sites must stay guarded: bp resume and ccmax restore.
        assertTrue(cmd.contains("if [ \"\$(getprop vtools.bp 2>/dev/null)\" = \"1\" ]"))
        assertTrue(cmd.contains("if [ -n \"\$restore\" ]"))
        // Backup resolution order: prop first, backup file second.
        assertTrue(cmd.contains("getprop vtools.charge.current.max"))
        assertTrue(cmd.contains("/data/adb/.scene_ccmax"))
        // Cleanup is unconditional (props/file only — never a charge node).
        assertTrue(cmd.contains("setprop vtools.bp 0"))
        assertTrue(cmd.contains("rm -f /data/adb/.scene_ccmax"))
    }

    @Test
    fun `no writes outside the two guards`() {
        val all = ChargeStockRestorer.command().lines()
        val isWrite = { line: String ->
            line.contains("> /sys/class/power_supply") || line.contains("> \$p") ||
                line.contains("> \"\$p\"") || line.contains("> \$B")
        }
        all.forEachIndexed { index, line ->
            if (!isWrite(line)) return@forEachIndexed
            val guarded = all.take(index).any {
                it.trimStart().startsWith("if [") || it.contains("for p in")
            }
            assertTrue("write outside guard: $line", guarded)
        }
    }

    @Test
    fun `hasArtifacts detects legacy state`() {
        assertFalse(ChargeStockRestorer.hasArtifacts("0", "", "", false))
        assertTrue(ChargeStockRestorer.hasArtifacts("1", "", "", false))
        assertTrue(ChargeStockRestorer.hasArtifacts("0", "3000000", "", false))
        assertTrue(ChargeStockRestorer.hasArtifacts("0", "", "1", false))
        assertTrue(ChargeStockRestorer.hasArtifacts("0", "", "", true))
    }
}
