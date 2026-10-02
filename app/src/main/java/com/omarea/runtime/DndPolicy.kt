package com.omarea.runtime

/**
 * DND-while-app-mode decision table (pure, JVM-tested).
 *
 * Encore semantics (docs/ATTRIBUTION.md): a game with its own mode can request
 * DND for the session; the previous interruption filter returns afterwards.
 * Scene maps "game" to "foreground app with an explicit per-app mode entry".
 *
 * Restoring our own change is always allowed — even under TRUE OFF — because
 * it is the equivalent of the unfreeze exception: leaving DND set would be a
 * leftover intervention. Engaging new DND never happens while blocked.
 *
 * Responsibility: decide, nothing else.
 * Non-goals: reading/writing DND ([DndController]).
 */
object DndPolicy {

    enum class Action { NONE, ENTER, EXIT }

    fun decide(
        enabled: Boolean,
        granted: Boolean,
        blocked: Boolean,
        appModeActive: Boolean,
        dndActive: Boolean
    ): Action = when {
        blocked || !enabled || !granted -> if (dndActive) Action.EXIT else Action.NONE
        appModeActive && !dndActive -> Action.ENTER
        !appModeActive && dndActive -> Action.EXIT
        else -> Action.NONE
    }
}
