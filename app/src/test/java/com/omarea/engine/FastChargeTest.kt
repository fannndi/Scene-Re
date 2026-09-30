package com.omarea.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fast-charge commands replaced `addin/fast_charge*.sh`; these tests pin
 * the behaviour that used to live in shell (mA→µA, step-up-only mode, the
 * mi11 node layout, node whitelist).
 */
class FastChargeTest {

    @Test
    fun `mi11 detection matches Build DEVICE values`() {
        assertTrue(FastCharge.isMi11Series("mars"))
        assertTrue(FastCharge.isMi11Series("star"))
        assertFalse(FastCharge.isMi11Series("surya"))
        assertFalse(FastCharge.isMi11Series(""))
    }

    @Test
    fun `run once prepares charge nodes and backs up current max`() {
        val cmd = FastCharge.runOnce()
        // safeguards relaxed (same nodes as the shell script)
        for (node in listOf(
            "/sys/class/qcom-battery/restricted_charging",
            "/sys/class/power_supply/usb/boost_current",
            "/sys/class/power_supply/battery/safety_timer_enabled",
            "/sys/class/power_supply/bms/temp_warm"
        )) {
            assertTrue("missing $node", cmd.contains(node))
        }
        // stock max backed up before we touch it
        assertTrue(cmd.contains("vtools.charge.current.max"))
        assertTrue(cmd.contains("cat /sys/class/power_supply/battery/constant_charge_current_max"))
        // and the boot-once marker is set
        assertTrue(cmd.contains("setprop vtools.fastcharge"))
    }

    @Test
    fun `limit converts mA to microamps`() {
        val cmd = FastCharge.limit(3000, onlyTaller = false, device = "surya")
        assertTrue(cmd.contains("echo 3000000"))
        assertTrue(cmd.contains("/sys/class/power_supply/*/constant_charge_current_max"))
    }

    @Test
    fun `step-up mode only raises the existing limit`() {
        val cmd = FastCharge.limit(2000, onlyTaller = true, device = "surya")
        // comparison guard present: write only when current is below target
        assertTrue(cmd.contains("-lt 2000000"))
        assertTrue(cmd.contains("cur=$(cat"))
        // plain mode has no comparison
        val plain = FastCharge.limit(2000, onlyTaller = false, device = "surya")
        assertFalse(plain.contains("-lt"))
    }

    @Test
    fun `mi11 uses constant_charge_current instead of the glob`() {
        val cmd = FastCharge.limit(3000, onlyTaller = false, device = "mars")
        assertTrue(cmd.contains("/sys/class/power_supply/battery/constant_charge_current"))
        assertFalse(cmd.contains("constant_charge_current_max"))
        assertFalse(cmd.contains("*"))
    }

    @Test
    fun `commands contain no obvious shell injection for numeric limits`() {
        val cmd = FastCharge.limit(4500, onlyTaller = false, device = "surya")
        assertTrue(cmd.contains("echo 4500000"))
        // pure digits only — a non-numeric limit must never pass through
        val digits = Regex("""echo (\d+)""").findAll(cmd).map { it.groupValues[1] }.toList()
        for (d in digits) {
            assertTrue(d.all { it.isDigit() })
        }
    }
}
