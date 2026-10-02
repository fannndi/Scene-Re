package com.omarea.runtime

import android.content.Context
import com.omarea.common.shell.ShellLog
import com.omarea.engine.HwuiController
import com.omarea.engine.ProfileController
import com.omarea.engine.RootShell
import com.omarea.util.CheckRootStatus

/**
 * Pre-uninstall cleanup ("leave no trace").
 *
 * Restores everything Scene changed outside its own data dir, in order:
 *  1. engine OFF → stock snapshot + MIUI daemons,
 *  2. replay [PmStateJournal] (unsuspend/enable/unhide, delete tweak settings),
 *  3. remove all Scene APatch modules + `/data/local/tmp` artifacts,
 *  4. clear HWUI overrides and the journal.
 *
 * The crash-safe counterpart for an uninstall without this step is the
 * [SceneGuard] boot script. Both share the same journal.
 *
 * Responsibility: the in-app cleanup sequence.
 * Non-goals: uninstalling the APK (the user does that).
 */
object SceneCleanup {

    const val RESULT_NO_ROOT = "no root"

    /** Blocking (root shell); run off the main thread. Returns a summary. */
    fun cleanupNow(context: Context): String {
        val app = context.applicationContext
        if (!CheckRootStatus.isAvailable()) return RESULT_NO_ROOT

        // 1. Engine OFF -> release to the per-boot stock snapshot.
        runCatching { ProfileController.setEngineEnabled(app, false, force = true) }

        // 1b. Disable every platform game-downscale override (persistent
        //     system setting; journal-mirrored for the uninstall guard).
        runCatching { DownscaleController.resetAll(app) }

        // 2. Undo journalled PM/settings changes.
        val restored = runCatching { PmStateJournal.restoreAll(app) }.getOrDefault(0)

        // 3. Remove every Scene module (sepolicy, hooks, rescue, guard) + tmp.
        RootShell.run(
            "rm -rf ${SceneGuard.DIR} /data/adb/modules/scene_sepolicy " +
                "/data/adb/modules/scene_systemless /data/adb/modules/scene_resurgence\n" +
                "rm -f /data/local/tmp/scene_thermald.sh /data/local/tmp/scene_thermald.profile_max " +
                "/data/local/tmp/scene_thermald.state /data/local/tmp/scene_thermald.stop " +
                "/data/local/tmp/scene_policy.rules\n" +
                "setprop persist.vtools.suspend ''"
        )

        // 4. HWUI overrides + journal reset.
        runCatching { HwuiController.clear(app) }
        PmStateJournal.clearAll(app)

        ShellLog.log("SceneCleanup", "cleanup done (restored=$restored)")
        return "restored=$restored"
    }
}
