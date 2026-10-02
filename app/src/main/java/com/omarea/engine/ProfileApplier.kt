package com.omarea.engine

import com.omarea.common.shell.ShellLog
import com.omarea.engine.PropShell
import com.omarea.engine.RootShell
import com.omarea.engine.ShellNodes

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
                # Only fix the mode when it blocks root (never downgrade a
                # 0666 node the direct-write whitelist relies on).
                [ -w "${'$'}1" ] || chmod 0664 "${'$'}1" 2>/dev/null
                echo "${'$'}2" > "${'$'}1" 2>/dev/null
            else
                # Locked parameter: the running kernel does not provide this
                # node. Report it instead of failing silently.
                echo "SCENE_MISSING:${'$'}1"
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
    fun writeThermalProfileMax(policy0Max: Long, policy6Max: Long, gpu: GpuThermal? = null) {
        // Extra fields stay whitespace-separated: the legacy shell daemon reads
        // fields 1/2 only, while the Kotlin guard also uses the GPU values.
        val gpuMax = gpu?.maxPwrLevel ?: -1
        val gpuDefault = gpu?.defaultPwrLevel ?: -1
        RootShell.run("echo '$policy0Max $policy6Max $gpuMax $gpuDefault' > ${ShellNodes.THERMALD_PROFILE_MAX}")
    }

    private fun runBlock(label: String, ops: List<ProfileOp>) {
        var direct = 0
        val remaining = ArrayList<ProfileOp>()
        if (directWrites) {
            for (op in ops) {
                // Skip the direct attempt entirely for families the boot probe
                // already proved denied (no avc noise, no wasted syscalls).
                if (SepolicyCapability.canWrite(op.node) == false) {
                    remaining.add(op)
                    continue
                }
                if (DirectWrite.write(op.node, op.value)) {
                    direct++
                    SepolicyCapability.mark(op.node, true)
                } else {
                    // Denial is real until the next reboot (module rules load
                    // at post-fs-data): remember it for this boot.
                    SepolicyCapability.mark(op.node, false)
                    remaining.add(op)
                }
            }
        } else {
            remaining.addAll(ops)
        }
        if (remaining.isEmpty()) {
            ShellLog.log("ProfileApplier.$label", "$direct ops written directly (no shell)")
            return
        }
        val script = HELPERS + "\n" + remaining.joinToString("\n") { "set_value '${it.node}' '${it.value}'" }
        val out = RootShell.run(script)
        val missing = out.lines()
            .filter { it.startsWith(MISSING_PREFIX) }
            .map { it.removePrefix(MISSING_PREFIX).trim() }
            .distinct()
        if (missing.isNotEmpty()) {
            ShellLog.log(
                "ProfileApplier.$label",
                "locked ${missing.size} op(s) — node not in kernel: ${missing.take(6)}",
                error = true
            )
        }
        ShellLog.log(
            "ProfileApplier.$label",
            "$direct direct, ${remaining.size} shell ops (${missing.size} locked) → ${out.take(160)}"
        )
    }

    private const val MISSING_PREFIX = "SCENE_MISSING:"

    /** Set by [ProfileController] before each apply (context-free here). */
    var directWrites: Boolean = false

    private fun verify(plan: ProfilePlan): List<String> {
        val diffs = ArrayList<String>()
        for (op in plan.ops) {
            val match = VERIFY_NODE.find(op.node) ?: continue
            val live = RootShell.read(op.node)
            if (live == op.value) continue
            if (VerifyPolicy.isAcceptedMismatch(op.node, op.value, live)) {
                // Kernel thermal mitigation is holding the max lower — expected.
                ShellLog.log(
                    "ProfileApplier.thermal",
                    "${match.groupValues[1]}.${match.groupValues[2]} held at $live (wanted ${op.value})"
                )
                continue
            }
            diffs += "${match.groupValues[1]}.${match.groupValues[2]}=$live(want ${op.value})"
        }
        return diffs
    }
}
