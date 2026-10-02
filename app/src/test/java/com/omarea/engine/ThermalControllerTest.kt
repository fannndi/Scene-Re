package com.omarea.engine

import com.omarea.engine.ThermalController.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity tests against `assets/scene_thermald.sh`: thresholds, hysteresis and
 * the restore condition must behave exactly like the shell daemon did.
 */
class ThermalControllerTest {

    // ------------------------------------------------------------ thresholds
    @Test
    fun `rising thresholds map to states`() {
        assertEquals(State.NORMAL, ThermalController.decide(390, null))
        assertEquals(State.WARM, ThermalController.decide(400, null))
        assertEquals(State.HOT, ThermalController.decide(440, null))
        assertEquals(State.CRITICAL, ThermalController.decide(470, null))
        assertEquals(State.CRITICAL, ThermalController.decide(520, null))
    }

    @Test
    fun `limit table matches the shell script`() {
        assertEquals(1843200L, State.WARM.limitKhz)
        assertEquals(1612800L, State.HOT.limitKhz)
        assertEquals(1248000L, State.CRITICAL.limitKhz)
        assertFalse(State.NORMAL.isClamped)
        assertTrue(State.WARM.isClamped)
        assertTrue(State.HOT.isClamped)
        assertTrue(State.CRITICAL.isClamped)
    }

    // ------------------------------------------------------------ hysteresis
    @Test
    fun `warm holds until below 38C`() {
        // 39C: fresh decision is NORMAL, but we came from warm -> stay warm
        assertEquals(State.WARM, ThermalController.decide(390, State.WARM))
        // 38C: still >= 380 -> hold
        assertEquals(State.WARM, ThermalController.decide(380, State.WARM))
        // 37C: 2C below entry -> finally leave
        assertEquals(State.NORMAL, ThermalController.decide(370, State.WARM))
    }

    @Test
    fun `hot holds until below 42C`() {
        assertEquals(State.HOT, ThermalController.decide(430, State.HOT)) // fresh: warm
        assertEquals(State.HOT, ThermalController.decide(420, State.HOT)) // boundary holds
        assertEquals(State.WARM, ThermalController.decide(410, State.HOT)) // released to warm
    }

    @Test
    fun `critical holds until below 45C`() {
        assertEquals(State.CRITICAL, ThermalController.decide(450, State.CRITICAL))
        assertEquals(State.HOT, ThermalController.decide(440, State.CRITICAL))
    }

    @Test
    fun `rising is immediate without hysteresis`() {
        assertEquals(State.HOT, ThermalController.decide(445, State.WARM))
        assertEquals(State.CRITICAL, ThermalController.decide(475, State.HOT))
        // warm -> normal direct drop (no prev threshold to honour beyond warm's)
        assertEquals(State.WARM, ThermalController.decide(395, State.WARM))
    }

    @Test
    fun `state flapping cannot occur at boundaries`() {
        // Walk down across a boundary in small steps: exactly one transition.
        var prev: State = State.HOT
        val seen = ArrayList<State>()
        for (t in 439 downTo 400 step 5) {
            prev = ThermalController.decide(t, prev)
            seen.add(prev)
        }
        // stays HOT until < 420, then WARM for the rest (never back to HOT)
        assertEquals(State.HOT, seen.first())
        assertEquals(State.WARM, seen.last())
        assertFalse(seen.contains(State.CRITICAL))
    }

    // ------------------------------------------------------------- restore
    @Test
    fun `restore runs on first run and after cooling only`() {
        assertTrue(ThermalController.shouldRestore(State.NORMAL, null))
        assertTrue(ThermalController.shouldRestore(State.NORMAL, State.HOT))
        assertTrue(ThermalController.shouldRestore(State.NORMAL, State.WARM))
        // already normal: no needless profile writes
        assertFalse(ThermalController.shouldRestore(State.NORMAL, State.NORMAL))
        // never restore while clamped
        assertFalse(ThermalController.shouldRestore(State.HOT, State.CRITICAL))
    }

    // --------------------------------------------------------------- parsing
    @Test
    fun `parseState round-trips every state and rejects junk`() {
        for (state in State.entries) {
            assertEquals(state, ThermalController.parseState(state.fileValue))
            assertEquals(state, ThermalController.parseState("  ${state.fileValue}\n"))
        }
        assertNull(ThermalController.parseState(null))
        assertNull(ThermalController.parseState(""))
        assertNull(ThermalController.parseState("bogus"))
    }

    @Test
    fun `parseTemp reads the battery node format`() {
        assertEquals(31, ThermalController.parseTemp("310"))
        assertEquals(45, ThermalController.parseTemp(" 455 "))
        assertEquals(4, ThermalController.parseTemp("47")) // 4.7C
        assertNull(ThermalController.parseTemp(""))
        assertNull(ThermalController.parseTemp(null))
        assertNull(ThermalController.parseTemp("n/a"))
    }

    // ----------------------------------------------------------- gpu cooling
    @Test
    fun `gpu limits deepen with every clamped state and stay in range`() {
        assertNull(State.NORMAL.gpuMaxPwrLevel)
        assertNull(State.NORMAL.gpuDefaultPwrLevel)
        assertEquals(4, State.WARM.gpuMaxPwrLevel)
        assertEquals(5, State.HOT.gpuMaxPwrLevel)
        assertEquals(5, State.CRITICAL.gpuMaxPwrLevel)
        // kgsl default_pwrlevel accepts at most num_pwrlevels-2 (=5 of 7)
        assertTrue(State.entries.mapNotNull { it.gpuDefaultPwrLevel }.all { it <= 5 })
    }

    @Test
    fun `gpu clamp target is lower-only`() {
        // live p2 (faster) than cap p4 -> deepen to p4
        assertEquals(4, ThermalController.gpuClampTarget(2, 4))
        // live p4 already as slow as the cap -> nothing to do
        assertNull(ThermalController.gpuClampTarget(4, 4))
        // live p6 (slower) -> never raise back up
        assertNull(ThermalController.gpuClampTarget(6, 4))
        assertNull(ThermalController.gpuClampTarget(null, 4))
        assertNull(ThermalController.gpuClampTarget(2, null))
    }

    @Test
    fun `profile limits parse cpu and gpu handoff fields`() {
        val limits = ThermalController.parseProfileLimits("1324800 1804800 5 6")
        assertEquals(1324800L, limits.policy0Max)
        assertEquals(1804800L, limits.policy6Max)
        assertEquals(5, limits.gpuMaxPwrLevel)
        assertEquals(6, limits.gpuDefaultPwrLevel)
    }

    @Test
    fun `profile limits tolerate the legacy two-field format`() {
        val limits = ThermalController.parseProfileLimits("1324800 1804800")
        assertEquals(1324800L, limits.policy0Max)
        assertNull(limits.gpuMaxPwrLevel)
        assertNull(limits.gpuDefaultPwrLevel)
        // negative placeholders mean "not managed"
        val placeholders = ThermalController.parseProfileLimits("-1 -1 -1 -1")
        assertNull(placeholders.policy0Max)
        assertNull(placeholders.gpuMaxPwrLevel)
        assertNull(ThermalController.parseProfileLimits(null).policy0Max)
    }

    @Test
    fun `anomaly rejection and ewma smoothing`() {
        // >10 C jump between samples is a sensor glitch
        assertTrue(ThermalController.isAnomaly(400, 511))
        assertTrue(ThermalController.isAnomaly(400, 289))
        assertFalse(ThermalController.isAnomaly(400, 409))
        assertFalse(ThermalController.isAnomaly(null, 999))

        // EWMA seeds with the raw sample and converges toward it
        assertEquals(400.0, ThermalController.smooth(null, 400), 0.001)
        assertEquals(403.0, ThermalController.smooth(400.0, 410), 0.001)
        assertEquals(409.0, ThermalController.smooth(409.0, 409), 0.001)

        // clamp-episode gain in whole Celsius
        assertEquals(2.0, ThermalController.thermalGainC(440, 420), 0.001)
        assertEquals(-1.5, ThermalController.thermalGainC(400, 415), 0.001)

        // predictive pre-clamp: rising fast above 38C clamps one step earlier
        assertEquals(
            ThermalController.State.WARM,
            ThermalController.withPreemption(ThermalController.State.NORMAL, 390, 1.2)
        )
        // slow rise or cool temperature keeps the base state
        assertEquals(
            ThermalController.State.NORMAL,
            ThermalController.withPreemption(ThermalController.State.NORMAL, 390, 0.2)
        )
        assertEquals(
            ThermalController.State.NORMAL,
            ThermalController.withPreemption(ThermalController.State.NORMAL, 370, 2.0)
        )
        // never lowers an already hotter state
        assertEquals(
            ThermalController.State.HOT,
            ThermalController.withPreemption(ThermalController.State.HOT, 450, 3.0)
        )
    }
}
