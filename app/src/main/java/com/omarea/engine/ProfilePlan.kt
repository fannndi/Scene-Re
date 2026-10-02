package com.omarea.engine

/**
 * A single sysfs write produced by the planner.
 */
data class ProfileOp(val node: String, val value: String)

/**
 * GPU thermal handoff values of a profile, mirrored by the thermal guard.
 *
 * Which key means what (Adreno kgsl): `max_pwrlevel` = the slowest level the
 * governor may pick (higher index = lower clock), `default_pwrlevel` = idle
 * level, `throttling` = kernel GPU thermal mitigation flag. The guard only
 * ever moves these to *slower* levels while hot and restores the profile
 * values when cool.
 */
data class GpuThermal(
    val maxPwrLevel: Int? = null,
    val defaultPwrLevel: Int? = null,
    val throttling: String? = null
)

/**
 * Execution plan for one profile application.
 *
 * Responsibility: carry an immutable, inspectable list of effects.
 * Non-goals: executing anything (see [ProfileApplier]).
 *
 * @property label human-readable plan name ("init", "balance", …)
 * @property ops sysfs writes in execution order
 * @property profileMax (policy0_max, policy6_max) handed to scene_thermald
 * @property profileGpu GPU levels handed to scene_thermald (thermal guard)
 * @property warnings non-fatal issues found while planning (bad governor, …)
 */
data class ProfilePlan(
    val label: String,
    val ops: List<ProfileOp>,
    val profileMax: Pair<Long, Long>? = null,
    val profileGpu: GpuThermal? = null,
    val warnings: List<String> = emptyList()
)
