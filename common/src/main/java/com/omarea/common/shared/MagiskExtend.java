package com.omarea.common.shared;

/**
 * Inert compatibility shim. Magisk support was removed from Scene-Re: the app now only uses
 * root (su) and Shizuku.
 *
 * Every method returns a "not available" answer, so all former Magisk code paths are skipped and
 * the plain shell-based fallbacks run instead. This class exists only so legacy dialogs still
 * compile; it can be deleted together with those dead branches.
 */
public class MagiskExtend {
    public static final String MAGISK_PATH = "/data/adb/magisk/";

    public static boolean magiskSupported() {
        return false;
    }

    public static boolean moduleInstalled() {
        return false;
    }

    public static String getMagiskReplaceFilePath(String path) {
        return path;
    }

    public static void magiskModuleInstall(android.content.Context context) {
    }

    public static boolean setSystemProp(String key, String value) {
        return false;
    }

    public static boolean deleteSystemPath(String path) {
        return false;
    }

    public static boolean createFileReplaceModule(String target, String source, String packageName, String appName) {
        return false;
    }

    public static boolean replaceSystemFile(String target, String source) {
        return false;
    }
}
