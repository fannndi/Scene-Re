package com.omarea.library.shell;

import com.omarea.common.shell.KeepShellPublic;
import com.omarea.common.shell.KernelProrp;
import com.omarea.common.shell.RootFile;
import com.omarea.model.CpuStatus;

import java.io.File;
import java.util.ArrayList;

/**
 * Adreno (Qualcomm Snapdragon) GPU helpers.
 * This project targets Qualcomm devices only, so non-Adreno paths are not supported.
 */
public class GpuUtils {
    private static String GPU_LOAD_PATH = null;
    private static String GPU_FREQ_CMD = null;

    private static boolean kgsGM = true;
    private static Boolean $isAdrenoGPU = null;
    private static String gpuParamsDirAdreno = "/sys/class/kgsl/kgsl-3d0";
    private static String gpuParamsDir = null;

    public static synchronized String getMemoryUsage() {
        // /sys/devices/virtual/kgsl/kgsl/page_alloc
        if (kgsGM) {
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

    public static synchronized String getGpuFreq() {
        if (GPU_FREQ_CMD == null) {
            String path1 = getGpuParamsDir() + "/cur_freq";
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
            String freq = KeepShellPublic.INSTANCE.doCmdSync(GPU_FREQ_CMD);
            if (freq.length() > 6) {
                return freq.substring(0, freq.length() - 6);
            }
            return freq;
        }
    }

    public static synchronized int getGpuLoad() {
        if (GPU_LOAD_PATH == null) {
            String[] paths = new String[]{
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
                return Integer.parseInt(load.replace("%", "").trim().split(" ")[0]);
            } catch (Exception ex) {
                return -1;
            }
        }
    }

    public static String[] getAvailableFreqs() {
        String freqs = KernelProrp.INSTANCE.getProp(getGpuParamsDir() + "/available_frequencies");
        return freqs.isEmpty() ? (new String[]{}) : freqs.split("[ ]+");
    }

    public static boolean supported() {
        return isAdrenoGPU();
    }

    public static synchronized boolean isAdrenoGPU() {
        if ($isAdrenoGPU == null) {
            $isAdrenoGPU = new File(gpuParamsDirAdreno).exists() || RootFile.INSTANCE.dirExists(gpuParamsDirAdreno);
        }
        return $isAdrenoGPU;
    }

    private static synchronized String getGpuParamsDir() {
        if (gpuParamsDir == null) {
            if (isAdrenoGPU()) {
                gpuParamsDir = gpuParamsDirAdreno + "/devfreq";
            } else {
                gpuParamsDir = "";
            }
        }
        return gpuParamsDir;
    }

    public static String[] getGovernors() {
        String g = KernelProrp.INSTANCE.getProp(getAvailableGovernorsPath());
        return g.isEmpty() ? (new String[]{}) : g.split("[ ]+");
    }

    public static String getMinFreq() {
        return KernelProrp.INSTANCE.getProp(getGpuParamsDir() + "/min_freq");
    }

    public static void setMinFreq(String value) {
        ArrayList<String> commands = new ArrayList<>();
        commands.add("chmod 0664 " + getGpuParamsDir() + "/min_freq;");
        commands.add("echo " + value + " > " + getGpuParamsDir() + "/min_freq;");
        KeepShellPublic.INSTANCE.doCmdSync(commands);
    }

    public static String getMaxFreq() {
        return KernelProrp.INSTANCE.getProp(getGpuParamsDir() + "/max_freq");
    }

    public static void setMaxFreq(String value) {
        ArrayList<String> commands = new ArrayList<>();
        commands.add("chmod 0664 " + getGpuParamsDir() + "/max_freq;");
        commands.add("echo " + value + " > " + getGpuParamsDir() + "/max_freq;");
        KeepShellPublic.INSTANCE.doCmdSync(commands);
    }

    public static String getGovernor() {
        return KernelProrp.INSTANCE.getProp(getGovernorPath());
    }

    public static void setGovernor(String value) {
        ArrayList<String> commands = new ArrayList<>();
        String governorPath = getGovernorPath();
        commands.add("chmod 0664 " + governorPath + ";");
        commands.add("echo " + value + " > " + governorPath + ";");
        KeepShellPublic.INSTANCE.doCmdSync(commands);
    }

    // #region Adreno GPU Power Level
    public static String getAdrenoGPUMinPowerLevel() {
        return KernelProrp.INSTANCE.getProp("/sys/class/kgsl/kgsl-3d0/min_pwrlevel");
    }

    public static void setAdrenoGPUMinPowerLevel(String value) {
        ArrayList<String> commands = new ArrayList<>();
        commands.add("chmod 0664 /sys/class/kgsl/kgsl-3d0/min_pwrlevel;");
        commands.add("echo " + value + " > /sys/class/kgsl/kgsl-3d0/min_pwrlevel;");
        KeepShellPublic.INSTANCE.doCmdSync(commands);
    }

    public static String getAdrenoGPUMaxPowerLevel() {
        return KernelProrp.INSTANCE.getProp("/sys/class/kgsl/kgsl-3d0/max_pwrlevel");
    }

    public static void setAdrenoGPUMaxPowerLevel(String value) {
        ArrayList<String> commands = new ArrayList<>();
        commands.add("chmod 0664 /sys/class/kgsl/kgsl-3d0/max_pwrlevel;");
        commands.add("echo " + value + " > /sys/class/kgsl/kgsl-3d0/max_pwrlevel;");
        KeepShellPublic.INSTANCE.doCmdSync(commands);
    }

    public static String getAdrenoGPUDefaultPowerLevel() {
        return KernelProrp.INSTANCE.getProp("/sys/class/kgsl/kgsl-3d0/default_pwrlevel");
    }

    public static void setAdrenoGPUDefaultPowerLevel(String value) {
        ArrayList<String> commands = new ArrayList<>();
        commands.add("chmod 0664 /sys/class/kgsl/kgsl-3d0/default_pwrlevel;");
        commands.add("echo " + value + " > /sys/class/kgsl/kgsl-3d0/default_pwrlevel;");
        KeepShellPublic.INSTANCE.doCmdSync(commands);
    }

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

    public static ArrayList<String> buildSetAdrenoGPUParams(CpuStatus cpuState) {
        ArrayList<String> commands = new ArrayList<>();
        // governor
        if (!cpuState.adrenoGovernor.equals("")) {
            String governorPath = getGovernorPath();
            commands.add("chmod 0664 " + governorPath + ";");
            commands.add("echo " + cpuState.adrenoGovernor + " > " + governorPath + ";");
        }
        // min freq
        if (!cpuState.adrenoMinFreq.equals("")) {
            commands.add("chmod 0664 " + getGpuParamsDir() + "/min_freq;");
            commands.add("echo " + cpuState.adrenoMinFreq + " > " + getGpuParamsDir() + "/min_freq;");
        }
        // max freq
        if (!cpuState.adrenoMaxFreq.equals("")) {
            commands.add("chmod 0664 " + getGpuParamsDir() + "/max_freq;");
            commands.add("echo " + cpuState.adrenoMaxFreq + " > " + getGpuParamsDir() + "/max_freq;");
        }

        // min power level
        if (!cpuState.adrenoMinPL.equals("")) {
            commands.add("chmod 0664 /sys/class/kgsl/kgsl-3d0/min_pwrlevel;");
            commands.add("echo " + cpuState.adrenoMinPL + " > /sys/class/kgsl/kgsl-3d0/min_pwrlevel;");
        }
        // max power level
        if (!cpuState.adrenoMaxPL.equals("")) {
            commands.add("chmod 0664 /sys/class/kgsl/kgsl-3d0/max_pwrlevel;");
            commands.add("echo " + cpuState.adrenoMaxPL + " > /sys/class/kgsl/kgsl-3d0/max_pwrlevel;");
        }
        // default power level
        if (!cpuState.adrenoDefaultPL.equals("")) {
            commands.add("chmod 0664 /sys/class/kgsl/kgsl-3d0/default_pwrlevel;");
            commands.add("echo " + cpuState.adrenoDefaultPL + " > /sys/class/kgsl/kgsl-3d0/default_pwrlevel;");
        }
        return commands;
    }

    private static String getAvailableGovernorsPath() {
        return getGpuParamsDir() + "/available_governors";
    }

    private static String getGovernorPath() {
        return getGpuParamsDir() + "/governor";
    }
}
