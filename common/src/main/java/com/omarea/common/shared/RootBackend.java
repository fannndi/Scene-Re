package com.omarea.common.shared;

import com.omarea.common.shell.KeepShellPublic;
import com.omarea.common.shell.RootFile;

import java.io.File;

/**
 * Root write layer: applies file overrides and runtime property changes.
 *
 * <h3>Design</h3>
 *
 * There is no proprietary module API involved. Two interchangeable strategies are tried
 * at runtime, via {@link #resolve()}:
 *
 * <ol>
 *   <li>{@link Backend#OVERLAY} — mirror the file into a directory that the boot process
 *       re-mounts over the original, so {@code /system} itself is never modified. This is
 *       non-destructive and survives an OTA. It is available whenever such a mount point
 *       exists on the device.</li>
 *   <li>{@link Backend#DIRECT} — remount the partition read/write and edit the file in
 *       place, keeping a one-time {@code .scene.bak} snapshot. This is the fallback that
 *       works on any rooted device with an unlocked bootloader, with no overlay at all.</li>
 * </ol>
 *
 * <p>Property changes are persisted for the next boot <em>and</em> applied live through
 * {@code resetprop} (falling back to {@code setprop}), so a density change is visible
 * immediately rather than only after a restart.
 *
 * <h3>Naming</h3>
 *
 * This class was previously called {@code MagiskExtend} and hardcoded paths belonging to a
 * specific third-party module framework, including {@code magisk -V} version probing and an
 * {@code imgtool}/{@code magisk.img} scheme. Those references are gone: the overlay location
 * is discovered from the device rather than assumed, and no feature depends on a particular
 * root manager being installed.
 *
 * <h3>Threading</h3>
 *
 * Every method performs blocking root shell I/O and must not be called on the main thread
 * of a responsive UI. Call sites already run inside root-operation flows.
 */
public class RootBackend {
    /**
     * Selected write strategy. The {@code code} values preserve the historical integer
     * constants ({@code 0/1/2}) that earlier callers compared against.
     */
    public enum Backend {
        /** No usable write target. */
        NONE(0),
        /** Writes are mirrored into an overlay directory that boot re-mounts. */
        OVERLAY(1),
        /** Writes go directly to the remounted partition. */
        DIRECT(2);

        public final int code;

        Backend(int code) {
            this.code = code;
        }
    }

    /** Directory holding Scene's own state on the data partition. */
    private static final String STATE_DIR = "/data/adb/scene";
    /** Marker file proving the overlay directory is usable. */
    private static final String OVERLAY_MARKER = "system.prop";

    /**
     * Overlay root with a trailing slash, or {@code "/"} when the direct backend is active.
     * Derived per device by {@link #resolve()} — never assumed.
     */
    private static String overlayPath = "/";

    // Resolution state. -1 = unresolved, 0 = unavailable, 1 = available.
    private static int supported = -1;
    private static Backend backend = Backend.NONE;
    private static String overlayRoot = null;
    private static int writablePartition = -1;

    // +----------------------------------------------------------------+
    // | Overlay discovery                                               |
    // +----------------------------------------------------------------+

    /**
     * Candidate overlay roots, most specific first.
     *
     * <p>{@code $MAGISK_MODULE} is consulted when the environment exports it, because it is
     * the authoritative per-boot path and is set by several different root managers. The
     * remaining entries are the conventional locations, probed in order.
     */
    private static String[] overlayCandidates() {
        String env = KeepShellPublic.INSTANCE.doCmdSync("echo -n \"$MAGISK_MODULE\"").trim();
        return new String[]{
                env,
                STATE_DIR + "/overlay",
                "/data/adb/modules",
                "/data/adb/modules_update",
        };
    }

    /**
     * First candidate that exists as a directory.
     *
     * @return absolute path without a trailing slash, or {@code null}.
     */
    private static String findOverlayRoot() {
        for (String candidate : overlayCandidates()) {
            if (candidate == null) {
                continue;
            }
            String path = candidate.trim();
            // Reject shell noise such as "error" or an unexpanded variable.
            if (path.isEmpty() || !path.startsWith("/") || path.contains("error")) {
                continue;
            }
            if (RootFile.INSTANCE.dirExists(path)) {
                return path;
            }
        }
        return null;
    }

    /**
     * Is the system partition writable? Probed by attempting a remount and then an actual
     * write, because {@code mount} reporting {@code rw} is not sufficient on devices with a
     * dm-verity or shared-block layout.
     */
    private static boolean probeWritablePartition() {
        String result = KeepShellPublic.INSTANCE.doCmdSync(
                "mount -o rw,remount /system 2>/dev/null; " +
                        "if touch /system/.scene_rw_probe 2>/dev/null; then " +
                        "  rm -f /system/.scene_rw_probe; echo -n 1; " +
                        "else echo -n 0; fi"
        ).trim();
        return result.endsWith("1");
    }

    /** Resolve the backend once per process. Overlay is preferred: it is non-destructive. */
    private static void resolve() {
        if (supported != -1) {
            return;
        }
        if (!RootFile.INSTANCE.dirExists("/system") || !KeepShellPublic.INSTANCE.checkRoot()) {
            supported = 0;
            backend = Backend.NONE;
            return;
        }

        String root = findOverlayRoot();
        if (root != null) {
            overlayRoot = root;
            overlayPath = root.endsWith("/") ? root : root + "/";
            backend = Backend.OVERLAY;
            supported = 1;
            return;
        }

        if (probeWritablePartition()) {
            writablePartition = 1;
            overlayRoot = null;
            overlayPath = "/";
            backend = Backend.DIRECT;
            supported = 1;
            return;
        }

        writablePartition = 0;
        backend = Backend.NONE;
        supported = 0;
    }

    /** The write strategy available on this device. Resolves on first call. */
    public static Backend backend() {
        resolve();
        return backend;
    }

    /** Human-readable description, for logs and diagnostics. */
    public static String backendDescription() {
        switch (backend()) {
            case OVERLAY:
                return "overlay at " + overlayPath;
            case DIRECT:
                return "direct partition write";
            default:
                return "unavailable";
        }
    }

    /** Cached root manager id; see {@link #manager()}. Not cached on failure. */
    private static volatile String manager = null;

    /**
     * Which root manager owns the {@code su} the app runs through:
     * {@code magisk | kernelsu | apatch | unknown | none}.
     *
     * <p>Probed once per process from the root shell: the daemon on PATH first
     * (FolkPatch-Re is an APatch fork that ships {@code apd} and uses it as the
     * {@code su} entry point as well, so both the binary and
     * {@code /data/adb/ap} count as {@code apatch}), then the manager's data
     * directory. Scripts receive this as {@code ROOT_MANAGER} instead of
     * re-probing the device, and the SELinux repair picks its policy tool from
     * the same id.
     */
    public static String manager() {
        String cached = manager;
        if (cached != null) {
            return cached;
        }
        String result = "unknown";
        try {
            String out = KeepShellPublic.INSTANCE.doCmdSync(
                    "if command -v magisk > /dev/null 2>&1; then echo magisk\n" +
                    "elif command -v ksud > /dev/null 2>&1; then echo kernelsu\n" +
                    "elif command -v apd > /dev/null 2>&1; then echo apatch\n" +
                    "elif [ -d /data/adb/ap ]; then echo apatch\n" +
                    "elif [ -d /data/adb/ksu ]; then echo kernelsu\n" +
                    "elif [ -d /data/adb/magisk ]; then echo magisk\n" +
                    "else echo unknown; fi").trim();
            if (out.isEmpty() || out.equalsIgnoreCase("error")) {
                // Root not available yet: report none but do not cache, so the
                // probe runs again after a grant.
                return "none";
            }
            result = out.split("\n")[0].trim();
        } catch (Exception ignored) {
        }
        manager = result;
        return result;
    }

    /**
     * Whether file and property overrides can be applied at all.
     *
     * <p>Returns {@code true} on either backend. It does <em>not</em> indicate that a
     * particular module framework is installed.
     */
    public static boolean supported() {
        resolve();
        return supported == 1;
    }

    /** True when writes are redirected through an overlay rather than applied in place. */
    public static boolean isOverlayActive() {
        resolve();
        return backend == Backend.OVERLAY;
    }

    /**
     * The overlay directory with a trailing slash, or {@code "/"} when writes go straight to
     * the partition. Resolves the backend on first call.
     */
    public static String getOverlayPath() {
        resolve();
        return overlayPath;
    }

    /**
     * Whether the overlay directory is present and initialised.
     *
     * <p>{@code false} on the direct backend, where there is no overlay by definition —
     * callers must treat this and {@link #supported()} as independent conditions.
     */
    public static boolean overlayReady() {
        resolve();
        if (backend != Backend.OVERLAY) {
            return false;
        }
        return RootFile.INSTANCE.dirExists(overlayPath);
    }

    /** Ensure the overlay directory exists. No-op on the direct backend. */
    private static void ensureOverlay() {
        if (backend != Backend.OVERLAY) {
            return;
        }
        if (!RootFile.INSTANCE.dirExists(overlayPath)) {
            KeepShellPublic.INSTANCE.doCmdSync("mkdir -p " + overlayPath);
        }
    }

    // +----------------------------------------------------------------+
    // | Path mapping                                                    |
    // +----------------------------------------------------------------+

    /**
     * Map an absolute system path to the path that should actually be written.
     *
     * <p>On the overlay backend the system layout is mirrored under the overlay root,
     * promoting {@code /vendor} and {@code /product} beneath a {@code system/} prefix the
     * way the overlay format expects. On the direct backend the path is returned unchanged,
     * because the real file is the target.
     */
    public static String getReplacePath(String systemPath) {
        resolve();
        if (backend != Backend.OVERLAY || overlayRoot == null) {
            return systemPath;
        }
        String relative = (systemPath.startsWith("/vendor") || systemPath.startsWith("/product"))
                ? "/system" + systemPath
                : systemPath;
        return overlayRoot + relative;
    }

    // +----------------------------------------------------------------+
    // | File overrides                                                  |
    // +----------------------------------------------------------------+

    /**
     * Make {@code originalPath} resolve to {@code newFile} instead of its own contents.
     *
     * <p>This is what lets an ordinary installed app behave like a system app. On the
     * overlay backend the file is mirrored and picked up on the next boot; on the direct
     * backend it is written over the live file, after snapshotting the original.
     *
     * @return whether the override was applied.
     */
    public static boolean createFileReplace(String originalPath, String newFile) {
        resolve();
        if (newFile == null || !RootFile.INSTANCE.itemExists(newFile)) {
            return false;
        }
        if (backend == Backend.DIRECT) {
            return writeDirectly(newFile, originalPath);
        }
        if (backend != Backend.OVERLAY) {
            return false;
        }

        ensureOverlay();
        String output = getReplacePath(originalPath);
        String dir = new File(output).getParent();
        KeepShellPublic.INSTANCE.doCmdSync(
                "mkdir -p \"" + dir + "\"\n" +
                        "cp -pdrf \"" + newFile + "\" \"" + output + "\"\n" +
                        "chmod -R 755 \"" + output + "\""
        );
        return RootFile.INSTANCE.fileExists(output);
    }

    /** Replace a file on the live partition, snapshotting the original once. */
    private static boolean writeDirectly(String source, String target) {
        if (!RootFile.INSTANCE.itemExists(target)) {
            return false;
        }
        String backup = target + ".scene.bak";
        KeepShellPublic.INSTANCE.doCmdSync(
                "mount -o rw,remount /system 2>/dev/null\n" +
                        "if [[ -e \"" + target + "\" ]] && [[ ! -e \"" + backup + "\" ]]; then cp -p \"" + target + "\" \"" + backup + "\"; fi\n" +
                        "cp -pdrf \"" + source + "\" \"" + target + "\"\n" +
                        "chmod 755 \"" + target + "\"\n" +
                        "sync"
        );
        return RootFile.INSTANCE.fileEquals(source, target);
    }

    /** Undo a previous {@link #createFileReplace}. */
    public static void removeFileOverride(String originalPath) {
        resolve();
        if (backend == Backend.DIRECT) {
            String backup = originalPath + ".scene.bak";
            KeepShellPublic.INSTANCE.doCmdSync(
                    "mount -o rw,remount /system 2>/dev/null\n" +
                            "if [[ -e \"" + backup + "\" ]]; then mv -f \"" + backup + "\" \"" + originalPath + "\"; fi\n" +
                            "sync"
            );
            return;
        }
        if (backend != Backend.OVERLAY) {
            return;
        }
        if (RootFile.INSTANCE.itemExists(originalPath)) {
            String output = getReplacePath(originalPath);
            KeepShellPublic.INSTANCE.doCmdSync("rm -rf \"" + output + "\"");
        }
    }

    // +----------------------------------------------------------------+
    // | Property overrides                                              |
    // +----------------------------------------------------------------+

    /**
     * Override a system property.
     *
     * <p>The value is recorded for the next boot and, additionally, pushed into the running
     * system immediately via {@code resetprop} — which can also replace read-only
     * properties — falling back to {@code setprop}. This removes the mandatory reboot that
     * the previous implementation required.
     *
     * @return whether the override was recorded.
     */
    public static boolean setSystemProp(String prop, String value) {
        resolve();
        if (prop == null || prop.isEmpty()) {
            return false;
        }

        if (backend == Backend.OVERLAY) {
            ensureOverlay();
            String propFile = overlayPath + "system.prop";
            KeepShellPublic.INSTANCE.doCmdSync(
                    "touch \"" + propFile + "\"\n" +
                            "sed -i '/^" + prop + "=/d' \"" + propFile + "\"\n" +
                            "echo '" + prop + "=\"" + value + "\"' >> \"" + propFile + "\""
            );
        } else if (backend == Backend.DIRECT) {
            setBuildPropDirect(prop, value);
        } else {
            return false;
        }

        applyPropLive(prop, value);
        return true;
    }

    /** Push a property into the running system so it takes effect without a reboot. */
    private static void applyPropLive(String prop, String value) {
        KeepShellPublic.INSTANCE.doCmdSync(
                "if command -v resetprop > /dev/null 2>&1; then resetprop -n '" + prop + "' '" + value + "'; " +
                        "else setprop '" + prop + "' '" + value + "'; fi"
        );
    }

    /**
     * Edit {@code build.prop} in place, preferring whichever partition already defines the
     * property. The pristine file is snapshotted to {@code .scene.bak} <em>before</em> the
     * first edit and only once, so repeated changes never overwrite the original.
     */
    private static void setBuildPropDirect(String prop, String value) {
        KeepShellPublic.INSTANCE.doCmdSync(
                "mount -o rw,remount /system 2>/dev/null\n" +
                        "target=/system/build.prop\n" +
                        "if [[ -f /vendor/build.prop ]] && grep -q \"^" + prop + "=\" /vendor/build.prop; then target=/vendor/build.prop; fi\n" +
                        "if [[ -f \"$target\" ]] && [[ ! -f \"$target.scene.bak\" ]]; then cp -p \"$target\" \"$target.scene.bak\"; fi\n" +
                        "tmp=\"$target.scene.tmp\"\n" +
                        "grep -v \"^" + prop + "=\" \"$target\" > \"$tmp\" 2>/dev/null\n" +
                        "echo '" + prop + "=\"" + value + "\"' >> \"$tmp\"\n" +
                        "cat \"$tmp\" > \"$target\"\n" +
                        "rm -f \"$tmp\"\n" +
                        "chmod 755 \"$target\"\n" +
                        "sync"
        );
    }

    /** Current contents of the persisted property overrides, or empty when unavailable. */
    public static String getProps() {
        resolve();
        if (backend == Backend.OVERLAY && RootFile.INSTANCE.fileExists(overlayPath + OVERLAY_MARKER)) {
            return KeepShellPublic.INSTANCE.doCmdSync("cat " + overlayPath + OVERLAY_MARKER);
        }
        return "";
    }

    /** Replace the persisted property overrides wholesale. */
    public static boolean updateProps(String fromFile) {
        resolve();
        if (fromFile == null || !RootFile.INSTANCE.fileExists(fromFile)) {
            return false;
        }
        if (backend != Backend.OVERLAY) {
            return false;
        }
        ensureOverlay();
        KeepShellPublic.INSTANCE.doCmdSync(
                "cp \"" + fromFile + "\" " + overlayPath + OVERLAY_MARKER + "\n" +
                        "chmod 644 " + overlayPath + OVERLAY_MARKER
        );
        return true;
    }

    // +----------------------------------------------------------------+
    // | Diagnostics                                                     |
    // +----------------------------------------------------------------+

    /**
     * One-line summary of the resolved backend, for the diagnostic log and the USB harness.
     * Logged at startup so a bug report shows which strategy was chosen.
     */
    public static String diagnose() {
        resolve();
        return "backend=" + backend() +
                " writable=" + (writablePartition == 1) +
                " overlay=" + (overlayRoot == null ? "none" : overlayRoot) +
                " root=" + KeepShellPublic.INSTANCE.checkRoot();
    }

    /** The directory Scene uses for its own state on the data partition. */
    public static String stateDir() {
        return STATE_DIR;
    }

    /**
     * Create {@link #STATE_DIR} if it does not exist.
     *
     * <p>Used by features that persist their own configuration there (swap, for instance)
     * so they survive a reboot without relying on any framework-specific directory.
     *
     * @return whether the directory exists afterwards.
     */
    public static boolean ensureStateDir() {
        if (RootFile.INSTANCE.dirExists(STATE_DIR)) {
            return true;
        }
        KeepShellPublic.INSTANCE.doCmdSync("mkdir -p " + STATE_DIR);
        return RootFile.INSTANCE.dirExists(STATE_DIR);
    }

    /** Clear cached resolution so the next call re-probes. Used by tests and diagnostics. */
    public static void resetCache() {
        supported = -1;
        backend = Backend.NONE;
        overlayRoot = null;
        overlayPath = "/";
        writablePartition = -1;
    }
}
