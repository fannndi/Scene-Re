package com.omarea.common.shell;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class ShellExecutor {
    private static String extraEnvPath = "";
    private static String defaultEnvPath = ""; // /sbin:/system/sbin:/system/bin:/system/xbin:/odm/bin:/vendor/bin:/vendor/xbin

    public static void setExtraEnvPath(String extraEnvPath) {
        ShellExecutor.extraEnvPath = extraEnvPath;
    }

    private static String getEnvPath() {
        // FIXME: In non-root mode the default TMPDIR=/data/local/tmp may make scripts that need a writable cache (e.g. using the source command) fail!
        if (extraEnvPath != null && !extraEnvPath.isEmpty()) {
            if (defaultEnvPath.isEmpty()) {
                try {
                    Process process = Runtime.getRuntime().exec("sh");
                    OutputStream outputStream = process.getOutputStream();
                    outputStream.write("echo $PATH".getBytes());
                    outputStream.flush();
                    outputStream.close();

                    InputStream inputStream = process.getInputStream();
                    byte[] cache = new byte[16384];
                    int length = inputStream.read(cache);
                    inputStream.close();
                    process.destroy();

                    String path = new String(cache, 0, length).trim();
                    if (path.length() > 0) {
                        defaultEnvPath = path;
                    } else {
                        throw new RuntimeException("Failed to get $PATH value");
                    }
                } catch (Exception ex) {
                    defaultEnvPath = "/sbin:/system/sbin:/system/bin:/system/xbin:/odm/bin:/vendor/bin:/vendor/xbin";
                }
            }

            String path = defaultEnvPath;

            return ( "PATH=" + path + ":" + extraEnvPath);
        }

        return null;
    }

    private static Process getProcess(String run) throws IOException {
        String env = getEnvPath();
        Runtime runtime = Runtime.getRuntime();
        /*
        // Some devices report an "Aborted" error
        if (env != null) {
            return runtime.exec(run, new String[]{
                env
            });
        }
        */
        Process process = runtime.exec(run);
        if (env != null) {
            OutputStream outputStream = process.getOutputStream();
            outputStream.write("export ".getBytes());
            outputStream.write(env.getBytes());
            outputStream.write("\n".getBytes());
            outputStream.flush();
        }
        return process;
    }

    public static Process getSuperUserRuntime() throws IOException {
        return getProcess("su");
    }

    public static Process getRuntime() throws IOException {
        return getProcess("sh");
    }

    /**
     * Returns the shell process for the currently selected privilege tier.
     * <p>
     * ROOT uses `su`, SHIZUKU uses the shell provided by the Shizuku user service,
     * NON_ROOT falls back to the app's own `sh`.
     */
    public static Process getPrivilegedRuntime() throws IOException {
        ShellMode mode = ShellModeProvider.INSTANCE.getMode();
        if (mode == ShellMode.SHIZUKU) {
            ShizukuShellProvider provider = ShellModeProvider.INSTANCE.getShizukuShellProvider();
            Process process = provider == null ? null : provider.createShell();
            if (process == null) {
                throw new IOException("Shizuku shell is not available");
            }
            return process;
        }
        if (mode == ShellMode.NON_ROOT) {
            return getRuntime();
        }
        return getSuperUserRuntime();
    }
}
