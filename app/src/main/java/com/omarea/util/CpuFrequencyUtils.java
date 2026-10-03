package com.omarea.util;

import com.omarea.common.shell.KeepShellPublic;
import com.omarea.common.shell.KernelProrp;
import com.omarea.util.SceneJNI;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;

public class CpuFrequencyUtils {
    private final String cpu_dir = "/sys/devices/system/cpu/cpu0/";
    private final String cpufreq_sys_dir = "/sys/devices/system/cpu/cpu0/cpufreq/";
    private final String scaling_cur_freq = cpufreq_sys_dir + "scaling_cur_freq";
    private final String scaling_governor = cpufreq_sys_dir + "scaling_governor";
    private ArrayList<String[]> cpuClusterInfo;
    private SceneJNI JNI = new SceneJNI();
    private int coreCount = -1;

    private String getCpuFreqValue(String path) {
        long freqValue = JNI.getKernelPropLong(path);
        if (freqValue > -1) {
            return "" + freqValue;
        }
        return "";
    }

    public String[] getAvailableFrequencies(Integer cluster) {
        if (cluster >= getClusterInfo().size()) {
            return new String[]{};
        }
        String cpu = "cpu" + getClusterInfo().get(cluster)[0];
        String[] frequencies;
        String scaling_available_freq = cpufreq_sys_dir + "scaling_available_frequencies";
        if (new File(scaling_available_freq.replace("cpu0", cpu)).exists()) {
            frequencies = KernelProrp.INSTANCE.getProp(scaling_available_freq.replace("cpu0", cpu)).split("[ ]+");
            return frequencies;
        } else if (new File("/sys/devices/system/cpu/cpufreq/mp-cpufreq/cluster" + cluster + "_freq_table").exists()) {
            frequencies = KernelProrp.INSTANCE.getProp("/sys/devices/system/cpu/cpufreq/mp-cpufreq/cluster" + cluster + "_freq_table")
                    .split("[ ]+");
            return frequencies;
        } else {
            return new String[]{};
        }
    }

    public String getCurrentFrequency(Integer cluster) {
        if (cluster >= getClusterInfo().size()) {
            return "";
        }

        String cpu = "cpu" + getClusterInfo().get(cluster)[0];
        return getCpuFreqValue(scaling_cur_freq.replace("cpu0", cpu));
    }

    public String getCurrentFrequency(String cpu) {
        return getCpuFreqValue(scaling_cur_freq.replace("cpu0", cpu));
    }

    public String[] getAvailableGovernors(Integer cluster) {
        if (cluster >= getClusterInfo().size()) {
            return new String[]{};
        }
        String cpu = "cpu" + getClusterInfo().get(cluster)[0];
        String scaling_available_governors = cpufreq_sys_dir + "scaling_available_governors";
        return KernelProrp.INSTANCE.getProp(scaling_available_governors.replace("cpu0", cpu)).split("[ ]+");
    }

    private String getCurrentScalingGovernor(String core) {
        return KernelProrp.INSTANCE.getProp(scaling_governor.replace("cpu0", core));
    }

    public HashMap<String, String> getCoregGovernorParams(Integer cluster) {
        String cpu = "cpu" + cluster;
        String governor = getCurrentScalingGovernor(cpu);
        return new FileValueMap().mapFileValue(cpu_dir.replace("cpu0", cpu) + "cpufreq/" + governor);
    }

    public boolean getCoreOnlineState(int coreIndex) {
        return KernelProrp.INSTANCE.getProp("/sys/devices/system/cpu/cpu0/online".replace("cpu0", "cpu" + coreIndex)).equals("1");
    }

    public void setCoreOnlineState(int coreIndex, boolean online) {
        ArrayList<String> commands = new ArrayList<>();
        commands.add("chmod 0755 /sys/devices/system/cpu/cpu0/online".replace("cpu0", "cpu" + coreIndex));
        commands.add("echo " + (online ? "1" : "0") + " > /sys/devices/system/cpu/cpu0/online".replace("cpu0", "cpu" + coreIndex));
        KeepShellPublic.INSTANCE.doCmdSync(commands);
    }

    public int getCoreCount() {
        if (coreCount > -1) {
            return coreCount;
        }
        int cores = 0;
        while (true) {
            File file = new File(cpu_dir.replace("cpu0", "cpu" + cores));
            if (file.exists()) {
                cores++;
            } else {
                break;
            }
        }
        coreCount = cores;
        return coreCount;
    }

    public ArrayList<String[]> getClusterInfo() {
        if (cpuClusterInfo != null) {
            return cpuClusterInfo;
        }
        synchronized (this) {
            int cores = 0;
            cpuClusterInfo = new ArrayList<>();
            ArrayList<String> clusters = new ArrayList<>();
            while (true) {
                File file = new File("/sys/devices/system/cpu/cpu0/cpufreq/related_cpus".replace("cpu0", "cpu" + cores));
                if (file.exists()) {
                    String relatedCpus = KernelProrp.INSTANCE.getProp("/sys/devices/system/cpu/cpu0/cpufreq/related_cpus".replace("cpu0", "cpu" + cores)).trim();
                    if (!clusters.contains(relatedCpus) && !relatedCpus.isEmpty()) {
                        clusters.add(relatedCpus);
                    }
                } else {
                    break;
                }
                cores++;
            }
            for (int i = 0; i < clusters.size(); i++) {
                cpuClusterInfo.add(clusters.get(i).split("[ ]+"));
            }
        }
        return cpuClusterInfo;
    }

}
