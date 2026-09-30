package com.omarea.engine

/**
 * Thermal guard state machine — the Kotlin port of `assets/scene_thermald.sh`.
 *
 * Responsibility: decide, from battery temperature + previous state, whether
 * scaling_max must be clamped (and to which limit) or restored to the active
 * profile's max. Pure logic, unit-tested.
 *
 * Safety contract (unchanged from the shell daemon):
 *  - only ever LOWERS scaling_max_freq; never touches min-freq, cores,
 *    governor or anything else;
 *  - hysteresis: leave a state only after the temperature drops 2C below its
 *    entry threshold, so the guard doesn't flap at the boundary;
 *  - kernel hardware thermal trips remain the final safety net.
 *
 * Responsibility: threshold table + hysteresis decisions.
 * Non-goals: reading nodes, spawning shells, running the loop (ThermalService).
 */
object ThermalController {

    /** Thresholds in deci-Celsius, identical to the shell script. */
    private const val CRITICAL_DECI = 470
    private const val HOT_DECI = 440
    private const val WARM_DECI = 400
    private const val HYST_WARM_DECI = 380
    private const val HYST_HOT_DECI = 420
    private const val HYST_CRITICAL_DECI = 450

    enum class State(val fileValue: String, val limitKhz: Long) {
        /** Cool enough: clamp lifted, profile max restored. */
        NORMAL("normal", 0L),
        WARM("warm", 1843200L),
        HOT("hot", 1612800L),
        CRITICAL("critical", 1248000L);

        val isClamped: Boolean get() = this != NORMAL
    }

    /** Parses the persisted state file content; unknown/empty → null (first run). */
    fun parseState(raw: String?): State? =
        State.entries.firstOrNull { it.fileValue == raw?.trim() }

    /**
     * Mirrors the shell hysteresis table: a downward transition is held back
     * until the temperature is 2C below the entry threshold of the state we
     * are leaving (e.g. warm→normal only when temp < 38C).
     */
    fun decide(tempDeciC: Int, prev: State?): State {
        var state = when {
            tempDeciC >= CRITICAL_DECI -> State.CRITICAL
            tempDeciC >= HOT_DECI -> State.HOT
            tempDeciC >= WARM_DECI -> State.WARM
            else -> State.NORMAL
        }
        if (prev != null && state != prev) {
            // The pairs below are all downward transitions by definition;
            // rising transitions fall through to `else` untouched.
            state = when {
                prev == State.WARM && state == State.NORMAL && tempDeciC >= HYST_WARM_DECI -> State.WARM
                prev == State.HOT && state == State.WARM && tempDeciC >= HYST_HOT_DECI -> State.HOT
                prev == State.CRITICAL && state == State.HOT && tempDeciC >= HYST_CRITICAL_DECI -> State.CRITICAL
                else -> state
            }
        }
        return state
    }

    /**
     * True when the profile max must be written back: we are in NORMAL and
     * either this is the first run (no previous state) or we just cooled
     * down from a clamping state — same conditions as the shell daemon.
     */
    fun shouldRestore(state: State, prev: State?): Boolean =
        state == State.NORMAL && (prev == null || prev != State.NORMAL)

    /** Parses `battery/temp` (deci-Celsius) → whole Celsius, or null. */
    fun parseTemp(raw: String?): Int? =
        raw?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()?.div(10)
}
