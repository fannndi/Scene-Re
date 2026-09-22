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
     */
    ParcelFileDescriptor[] openShell(in String[] command) = 2;

    void closeShell() = 3;
}
