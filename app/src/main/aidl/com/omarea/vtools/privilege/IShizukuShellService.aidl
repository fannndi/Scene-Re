package com.omarea.vtools.privilege;

import android.os.ParcelFileDescriptor;

/**
 * Shell host running inside a process created by the Shizuku server.
 * The method IDs of the Shizuku reserved methods must not be changed.
 */
interface IShizukuShellService {

    void destroy() = 16777114; // Reserved destroy method defined by the Shizuku server

    int getUid() = 1;

    /**
     * Opens a persistent shell process and returns three pipe ends:
     * [0] stdin write end, [1] stdout read end, [2] stderr read end.
     *
     * The caller passes a one-element int array and the shell's id is written into it. Shells are
     * independent: opening a second one must not disturb the first, because the app keeps more than
     * one persistent shell alive (KeepShellPublic's default and secondary instances). This was a
     * single-shell host that closed the previous process on every open, which silently killed one
     * of the app's shells - its reader then hit end-of-stream and returned empty output for commands
     * that had in fact succeeded.
     */
    ParcelFileDescriptor[] openShell(in String[] command, inout int[] shellId) = 2;

    /** Closes the shell identified by [shellId]; other shells keep running. */
    void closeShell(int shellId) = 3;
}
