package com.omarea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser tests for the Tweaks batch loader: the whole screen reads the device
 * in ONE root-shell round trip, so these pure parsers are the only thing
 * standing between a broken screen and a working one.
 */
class TweakCommandsBatchTest {

    private fun sectionsFixture(): String = """
        @@low_power@@
        0
        @@trigger_level@@
        14
        @@trigger_max@@
        null
        @@anim@@
        window=1
        transition=0.5
        ratio=null
        @@ufs@@
        bDeviceLifeTimeEstA=0x01
        --- device ---
        bDeviceLifeTimeEstB=0x02
        @@sensors@@
        thermal_zone0|cpu-thermal|45000
        thermal_zone3|battery|31000
        @@bus@@
        DDR|1|300000|2016000|0|300000 2016000
        DDRQOS|0||||
        L3|1|403200|1804800|1804800|403200 1804800
        @@module_installed@@
        1
        @@hooked_perfboosts@@
        0
        @@rescue@@
        1
        trailing junk without marker
    """.trimIndent()

    @Test
    fun `parseSections splits markers and trims bodies`() {
        val sections = TweakCommands.parseSections(sectionsFixture())

        assertEquals("0", sections["low_power"])
        assertEquals("14", sections["trigger_level"])
        assertEquals("null", sections["trigger_max"])
        assertEquals("1", sections["module_installed"])
        assertEquals("0", sections["hooked_perfboosts"])
        // junk after the last marker stays inside the last section (first line)
        assertEquals("1", sections["rescue"]!!.lines().first())
    }

    @Test
    fun `parseSections keeps multi-line bodies intact`() {
        val sections = TweakCommands.parseSections(sectionsFixture())

        assertEquals(
            "window=1\ntransition=0.5\nratio=null",
            sections["anim"]
        )
        assertTrue(sections.getValue("ufs").contains("--- device ---"))
        assertEquals(2, TweakCommands.parseSensors(sections.getValue("sensors")).size)
        // junk after the last marker stays inside the last section
        assertTrue(sections.getValue("rescue").contains("trailing junk"))
    }

    @Test
    fun `parseSections tolerates empty and unknown input`() {
        assertTrue(TweakCommands.parseSections("").isEmpty())
        assertTrue(TweakCommands.parseSections("no markers here").isEmpty())
        val partial = TweakCommands.parseSections("@@only@@\nvalue")
        assertEquals("value", partial["only"])
    }

    @Test
    fun `parseBusRows reads visible and invisible domains`() {
        val rows = TweakCommands.parseBusRows(TweakCommands.parseSections(sectionsFixture()).getValue("bus"))

        assertEquals(3, rows.size)
        val ddr = rows[0]
        assertEquals("DDR", ddr.id)
        assertTrue(ddr.visible)
        assertEquals("300000", ddr.min)
        assertEquals("2016000", ddr.max)
        assertEquals("0", ddr.boost)
        assertEquals(listOf("300000", "2016000"), ddr.options)

        val ddqos = rows[1]
        assertEquals("DDRQOS", ddqos.id)
        assertTrue(!ddqos.visible)
        assertTrue(ddqos.options.isEmpty())
    }

    @Test
    fun `parseBusRows skips junk lines`() {
        val rows = TweakCommands.parseBusRows("\njust noise\nDDR|1|1|2|3|9\n")
        assertEquals(1, rows.size)
        assertEquals("DDR", rows[0].id)
    }

    @Test
    fun `tweaksLoadScript wraps every probe in a unique marker`() {
        val script = TweakCommands.tweaksLoadScript()
        // every probe is emitted as: echo "@@key@@"
        val echoMarker = Regex("""echo "@@([A-Za-z0-9_]+)@@" """.trimEnd())
        val markers = script.lines().mapNotNull { echoMarker.find(it)?.groupValues?.get(1) }

        assertTrue("script too small: ${script.length}", script.length > 200)
        assertEquals("markers not unique: $markers", markers.size, markers.toSet().size)
        for (required in listOf(
            "low_power", "trigger_level", "anim", "gapps_supported", "ufs",
            "sensors", "perfmgr_supported", "ddr_fixed", "bus",
            "module_installed", "hooks", "rescue"
        )) {
            assertTrue("missing marker $required", markers.contains(required))
        }
    }

    @Test
    fun `parseHookRows reads the hook lines`() {
        val rows = TweakCommands.parseHookRows(
            """
            /system/vendor/etc/perf/perfboostsconfig.xml|1|0
            /system/vendor/bin/hw/vendor.qti.hardware.perf@2.2-service|1|1
            /system_ext/bin/perfservice|0|0
            noise line without pipes
            """.trimIndent()
        )
        assertEquals(3, rows.size)
        assertEquals("/system/vendor/etc/perf/perfboostsconfig.xml", rows[0].target)
        assertTrue(rows[0].supported)
        assertTrue(!rows[0].hooked)
        assertTrue(rows[1].hooked)
        assertTrue(!rows[2].supported)
    }

    @Test
    fun `hook script covers every target exactly once`() {
        val script = TweakCommands.tweaksLoadScript()
        for (target in ModuleHooks.targets) {
            // supported-probe (path + .bak) and hooked-probe both reference it
            val occurrences = Regex(Regex.escape(target)).findAll(script).count()
            assertTrue(
                "target $target not wired in batch script (found $occurrences)",
                occurrences >= 3
            )
        }
    }

    @Test
    fun `legacy parsers still handle their formats`() {
        assertEquals(mapOf("window" to "1", "ratio" to "null"),
            TweakCommands.parseKeyValues("window=1\nratio=null"))
        assertEquals(
            Triple("thermal_zone0", "cpu-thermal", "45000"),
            TweakCommands.parseSensors("thermal_zone0|cpu-thermal|45000").first()
        )
        // lines that aren't zone|type|temp are dropped, not crashed on
        assertTrue(TweakCommands.parseSensors("garbage line").isEmpty())
        assertEquals("0x03", TweakCommands.parseUfs("bDeviceLifeTimeEstA = 0x03")["bDeviceLifeTimeEstA"])
    }
}
