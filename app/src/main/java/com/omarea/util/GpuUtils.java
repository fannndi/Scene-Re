package com.omarea.util;

import com.omarea.common.shell.KeepShellPublic;
import com.omarea.common.shell.KernelProrp;
import com.omarea.common.shell.RootFile;

import java.io.File;
import java.util.ArrayList;

public class GpuUtils {
    private static String GPU_LOAD_PATH = null;
    private static String GPU_FREQ_CMD = null;


    private static boolean kgsGM = true;
    private static Boolean $isAdrenoGPU = null;
    private static String gpuParamsDirAdreno = "/sys/class/kgsl/kgsl-3d0";
    private static String gpuParamsDir = null;

    public static String getMemoryUsage() {
        if (kgsGM) {
            // /sys/devices/virtual/kgsl/kgsl/page_alloc
            String bytes = KeepShellPublic.INSTANCE.doCmdSync("cat /sys/devices/virtual/kgsl/kgsl/page_alloc");
            try {
                long b = (Long.parseLong(bytes));
                return (b / 1024 / 1024) + "MB";
            } catch (Exception ex) {
                kgsGM = false;
            }
        }
        return null;
    }

    public static String getGpuFreq() {
        if (GPU_FREQ_CMD == null) {
            String path1 = getGpuParamsDir() + "/cur_freq"; // 骁龙
            String path2 = "/sys/kernel/gpu/gpu_clock";
            if (RootFile.INSTANCE.fileExists(path1)) {
                GPU_FREQ_CMD = "cat " + path1;
            } else if (RootFile.INSTANCE.fileExists(path2)) {
                GPU_FREQ_CMD = "cat " + path2;
            } else {
                GPU_FREQ_CMD = "";
            }
        }

        if (GPU_FREQ_CMD.isEmpty()) {
            return "";
        } else {
            String raw = KeepShellPublic.INSTANCE.doCmdSync(GPU_FREQ_CMD).trim();
            if (raw.isEmpty() || raw.equals("error")) {
                return "";
            }
            try {
                long value = (long) Double.parseDouble(raw.split("\\s+")[0]);
                // Normalise Hz / kHz / MHz to MHz (the old substring(length-6)
                // trick silently mangled kHz values).
                if (value >= 100_000_000L) {
                    return "" + (value / 1_000_000L);
                } else if (value >= 100_000L) {
                    return "" + (value / 1_000L);
                }
                return "" + value;
            } catch (Exception ex) {
                return raw;
            }
        }
    }

    public static int getGpuLoad() {
        if (GPU_LOAD_PATH == null) {
            String[] paths = new String[]{
                    // 旧骁龙
                    "/sys/kernel/gpu/gpu_busy",
                    // 骁龙
                    "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
                    "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
                    "/sys/class/kgsl/kgsl-3d0/gpuload"
            };
            GPU_LOAD_PATH = "";
            for (String path : paths) {
                if (RootFile.INSTANCE.fileExists(path)) {
                    GPU_LOAD_PATH = path;
                    break;
                }
            }
        }

        if (GPU_LOAD_PATH.equals("")) {
            return -1;
        } else {
            String load = KernelProrp.INSTANCE.getProp(GPU_LOAD_PATH);
            try {
                int value = (int) Double.parseDouble(load.replace("%", "").trim().split(" ")[0]);
                if (value < 0) {
                    return -1;
                }
                return Math.min(100, value);
            } catch (Exception ex) {
                return -1;
            }
        }
    }

    // Adreno /sys/class/kgsl/kgsl-3d0/freq_table_mhz
    public static String[] getFreqTableMhz() {
        if (isAdrenoGPU()) {
            String freqs = KernelProrp.INSTANCE.getProp(gpuParamsDirAdreno + "/freq_table_mhz");
            if (!freqs.isEmpty()) {
                return freqs.split("[ ]+");
            }
        }
        return new String[]{};
    }

    public static boolean supported() {
        return isAdrenoGPU();
    }

    public static boolean isAdrenoGPU() {
        if ($isAdrenoGPU == null) {
            $isAdrenoGPU = new File(gpuParamsDirAdreno).exists() || RootFile.INSTANCE.dirExists(gpuParamsDirAdreno);
        }
        return $isAdrenoGPU;
    }

    private static String getGpuParamsDir() {
        if (gpuParamsDir == null) {
            if (isAdrenoGPU()) {
                gpuParamsDir = gpuParamsDirAdreno + "/devfreq";
            } else {
                gpuParamsDir = "";
            }
        }
        return gpuParamsDir;
    }

    public static String getMinFreq() {
        return KernelProrp.INSTANCE.getProp(getGpuParamsDir() + "/min_freq");
    }

    public static String getMaxFreq() {
        return KernelProrp.INSTANCE.getProp(getGpuParamsDir() + "/max_freq");
    }

    public static String getGovernor() {
        return KernelProrp.INSTANCE.getProp(getGovernorPath());
    }

    // #region Adreno GPU Power Level
    public static String[] getAdrenoGPUPowerLevels() {
        String leves = KernelProrp.INSTANCE.getProp("/sys/class/kgsl/kgsl-3d0/num_pwrlevels");
        try {
            int max = Integer.parseInt(leves);
            ArrayList<String> arr = new ArrayList<>();
            for (int i = 0; i < max; i++) {
                arr.add("" + i);
            }
            return arr.toArray(new String[0]);
        } catch (Exception ignored) {
        }
        return new String[]{};
    }
    // #endregion Adreno GPU Power Level

    private static String getGovernorPath() {
        String base = getGpuParamsDir();
        String governors = base + "/governors";
        if (RootFile.INSTANCE.fileExists(governors) || new File(governors).exists()) {
            return governors;
        }
        return base + "/governor";
    }
}
