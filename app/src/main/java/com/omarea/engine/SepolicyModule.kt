package com.omarea.engine

import com.omarea.common.shell.ShellLog

/**
 * Boot-time SELinux rules via a tiny APatch/Magisk module (`sepolicy.rule`).
 *
 * Why: APatch's runtime `magiskpolicy --live` accepts rules and the policy
 * blob shown by `--print-rules` contains them, but the KernelPatch-hooked
 * enforcement keeps using its boot-time state (`runtime policy authentication
 * unavailable` in apd) — a runtime load is therefore NOT effective on this
 * device. APatch applies a module's `sepolicy.rule` at post-fs-data, which
 * goes through the working path, so the app provisions this module
 * automatically and rules take effect on the next boot.
 *
 * The runtime apply is kept as a best-effort convenience; this module is the
 * durable mechanism.
 *
 * Responsibility: create/update the module directory (never its removal).
 * Non-goals: building rules (see [SepolicyOptimizer]).
 */
object SepolicyModule {

    const val ID = "scene_sepolicy"
    const val DIR = "/data/adb/modules/$ID"

    /** True when the module directory exists. */
    fun isInstalled(): Boolean =
        RootShell.run("[ -d $DIR ] && echo 1 || echo 0").trim() == "1"

    fun hasRules(): Boolean =
        RootShell.run("[ -s $DIR/sepolicy.rule ] && echo 1 || echo 0").trim() == "1"

    /**
     * Writes module.prop + sepolicy.rule with the current rule set.
     * Returns true when the module was written without shell errors.
     */
    fun sync(rules: String): Boolean {
        val prop = """
            id=$ID
            name=Scene SELinux rules
            version=$VERSION
            versionCode=1
            author=Scene
            description=Scoped magiskpolicy rules (read rules + opt-in write rules). Managed by the Scene app.
        """.trimIndent()
        val out = RootShell.run(
            "mkdir -p $DIR\n" +
                "cat > $DIR/module.prop <<'SCENE_MODULE'\n$prop\nSCENE_MODULE\n" +
                "cat > $DIR/sepolicy.rule <<'SCENE_RULES'\n$rules\nSCENE_RULES\n" +
                "rm -f $DIR/disable $DIR/remove\n" +
                "chmod 0644 $DIR/module.prop $DIR/sepolicy.rule\n" +
                "echo synced"
        )
        val ok = out.contains("synced")
        ShellLog.log(
            "SepolicyModule.sync",
            if (ok) "module written (${rules.lines().size} rules, reboot to apply)" else out.take(200),
            error = !ok
        )
        return ok
    }

    private const val VERSION = "1.0"
}
