package com.omarea.core.profile

/**
 * A single sysfs write produced by the planner.
 */
data class ProfileOp(val node: String, val value: String)

/**
 * Execution plan for one profile application.
 *
 * Responsibility: carry an immutable, inspectable list of effects.
 * Non-goals: executing anything (see [ProfileApplier]).
 *
 * @property label human-readable plan name ("init", "balance", …)
 * @property ops sysfs writes in execution order
 * @property profileMax (policy0_max, policy6_max) handed to scene_thermald
 * @property warnings non-fatal issues found while planning (bad governor, …)
 */
data class ProfilePlan(
    val label: String,
    val ops: List<ProfileOp>,
    val profileMax: Pair<Long, Long>? = null,
    val warnings: List<String> = emptyList()
)
