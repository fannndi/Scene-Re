package com.omarea.core.profile

import com.omarea.common.shell.ShellLog
import com.omarea.core.shell.PropShell
import com.omarea.core.shell.RootShell
import com.omarea.core.shell.ShellNodes

/**
 * Executes [ProfilePlan]s and verifies the CPU writes.
 *
 * All applies are single-flight (one plan at a time). Verification compares
 * the *planned* values (already clamped by the planner) with the live nodes
 * and retries the whole block once on mismatch.
 *
 * Responsibility: plan execution + read-back verification.
 * Non-goals: planning, daemons, properties.
 */
object ProfileApplier {

    /** Helper script prepended to every block (same semantics as powercfg-utils.sh). */
    private val HELPERS = """
        set_value() {
            if [ -f "${'$'}1" ]; then
                chmod 0664 "${'$'}1" 2>/dev/null
                echo "${'$'}2" > "${'$'}1" 2>/dev/null
            fi
        }
    """.trimIndent()

    private val applyLock = Any()

    private val VERIFY_NODE =
        Regex("""cpufreq/(policy\d+)/(scaling_governor|scaling_min_freq|scaling_max_freq)$""")

    /** Runs [plan]; returns mismatch descriptions (empty when verified). */
    fun apply(plan: ProfilePlan): List<String> {
        if (plan.ops.isEmpty()) return emptyList()
        synchronized(applyLock) {
            plan.warnings.forEach {
                ShellLog.log("ProfilePlanner.${plan.label}", it, error = true)
            }
            runBlock(plan.label, plan.ops)

            var diffs = verify(plan)
            if (diffs.isNotEmpty()) {
                ShellLog.log("ProfileApplier.verify", "${plan.label}: ${diffs.size} mismatch, retrying", error = true)
                runBlock("${plan.label}-retry", plan.ops)
                diffs = verify(plan)
                if (diffs.isNotEmpty()) {
                    ShellLog.log("ProfileApplier.verify", "${plan.label}: still mismatched: $diffs", error = true)
                }
            }
            return diffs
        }
    }

    /** Hands the active profile's max frequencies to scene_thermald. */
    fun writeThermalProfileMax(policy0Max: Long, policy6Max: Long) {
        RootShell.run("echo '$policy0Max $policy6Max' > ${ShellNodes.THERMALD_PROFILE_MAX}")
    }

    private fun runBlock(label: String, ops: List<ProfileOp>) {
        val script = HELPERS + "\n" + ops.joinToString("\n") { "set_value '${it.node}' '${it.value}'" }
        val out = RootShell.run(script)
        ShellLog.log("ProfileApplier.$label", "${ops.size} ops → ${out.take(200)}")
    }

    private fun verify(plan: ProfilePlan): List<String> {
        val diffs = ArrayList<String>()
        for (op in plan.ops) {
            val match = VERIFY_NODE.find(op.node) ?: continue
            val live = RootShell.read(op.node)
            if (live != op.value) {
                diffs += "${match.groupValues[1]}.${match.groupValues[2]}=$live(want ${op.value})"
            }
        }
        return diffs
    }
}
