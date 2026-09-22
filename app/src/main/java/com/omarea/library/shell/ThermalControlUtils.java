package com.omarea.library.shell;

import com.omarea.common.shell.KeepShellPublic;
import com.omarea.common.shell.KernelProrp;
import com.omarea.common.shell.RootFile;
import com.omarea.common.shell.ShellCapability;
import com.omarea.common.shell.ShellCapabilityRegistry;
import com.omarea.model.CpuStatus;

import java.util.ArrayList;

/**
 * Qualcomm thermal controls.
 *
 * Older Qualcomm kernels expose an `msm_thermal` module with `core_control`, `vdd_restriction`
 * and `parameters/enabled`. Newer MIUI kernels (including the surya MIUI 13 ROM this fork targets)
 * do not ship that module at all; they drive thermal through the generic
 * `/sys/class/thermal/thermal_zone*` interfaces instead.
 *
 * This class therefore discovers which control surface the running kernel actually provides and
 * reports "unsupported" honestly instead of issuing writes against a non-existent path. The
 * discovery result is cached because it costs a shell round trip.
 */
public class ThermalControlUtils {
    // Legacy Qualcomm module surface.
    private static final String LEGACY_CORE_CONTROL = "/sys/module/msm_thermal/core_control/enabled";
    private static final String LEGACY_VDD_RESTRICTION = "/sys/module/msm_thermal/vdd_restriction/enabled";
    private static final String LEGACY_PARAMETERS = "/sys/module/msm_thermal/parameters/enabled";

    // Generic thermal framework surface, used by kernels without msm_thermal.
    private static final String THERMAL_ZONE0_MODE = "/sys/class/thermal/thermal_zone0/mode";

    private static volatile Boolean legacyAvailable = null;
    private static volatile Boolean genericAvailable = null;

    private final String thermal_core_control = LEGACY_CORE_CONTROL;
    private final String thermal_vdd_restriction = LEGACY_VDD_RESTRICTION;
    private final String thermal_parameters = LEGACY_PARAMETERS;

    /**
     * True when this device exposes any writable thermal control.
     *
     * Consults the runtime capability snapshot first so that a Shizuku or non-root session reports
     * unsupported without paying for a shell round trip.
     */
    public Boolean isSupported() {
        if (!ShellCapabilityRegistry.INSTANCE.supports(ShellCapability.THERMAL_SYSFS_WRITE)) {
            return false;
        }
        return hasLegacySurface() || hasGenericSurface();
    }

    /** True when the legacy `msm_thermal` module is present. */
    public boolean hasLegacySurface() {
        if (legacyAvailable == null) {
            legacyAvailable = RootFile.INSTANCE.itemExists(LEGACY_CORE_CONTROL) ||
                    RootFile.INSTANCE.itemExists(LEGACY_VDD_RESTRICTION) ||
                    RootFile.INSTANCE.itemExists(LEGACY_PARAMETERS);
        }
        return legacyAvailable;
    }

    /** True when the generic thermal framework offers a writable zone mode. */
    public boolean hasGenericSurface() {
        if (genericAvailable == null) {
            genericAvailable = RootFile.INSTANCE.itemExists(THERMAL_ZONE0_MODE);
        }
        return genericAvailable;
    }

    /**
     * Short description of the active control surface, for the UI.
     * Returns an empty string when nothing is available.
     */
    public String getSurfaceDescription() {
        if (hasLegacySurface()) {
            return "msm_thermal";
        }
        if (hasGenericSurface()) {
            return "thermal_zone";
        }
        return "";
    }

    public String getCoreControlState() {
        if (!hasLegacySurface()) {
            return "";
        }
        return KernelProrp.INSTANCE.getProp(thermal_core_control).trim();
    }

    public void setCoreControlState(Boolean online) {
        if (!hasLegacySurface()) {
            return;
        }
        String val = online ? "1" : "0";
        ArrayList<String> commands = new ArrayList<>();
        commands.add("chmod 0664 " + thermal_core_control);
        commands.add("echo " + val + " > " + thermal_core_control);
        KeepShellPublic.INSTANCE.doCmdSync(commands);
    }

    public String getVDDRestrictionState() {
        if (!hasLegacySurface()) {
            return "";
        }
        return KernelProrp.INSTANCE.getProp(thermal_vdd_restriction).trim();
    }

    public void setVDDRestrictionState(Boolean online) {
        if (!hasLegacySurface()) {
            return;
        }
        String val = online ? "1" : "0";
        ArrayList<String> commands = new ArrayList<>();
        commands.add("chmod 0664 " + thermal_vdd_restriction);
        commands.add("echo " + val + " > " + thermal_vdd_restriction);
        KeepShellPublic.INSTANCE.doCmdSync(commands);
    }

    public String getTheramlState() {
        if (!hasLegacySurface()) {
            return "";
        }
        return KernelProrp.INSTANCE.getProp(thermal_parameters).trim();
    }

    public void setTheramlState(Boolean online) {
        if (!hasLegacySurface()) {
            return;
        }
        String val = online ? "Y" : "N";
        ArrayList<String> commands = new ArrayList<>();
        commands.add("chmod 0664 " + thermal_parameters);
        commands.add("echo " + val + " > " + thermal_parameters);
        KeepShellPublic.INSTANCE.doCmdSync(commands);
    }

    public ArrayList<String> buildSetThermalParams(CpuStatus cpuStatus, ArrayList<String> commands) {
        if (!hasLegacySurface()) {
            // No legacy module on this kernel: do not emit writes against missing nodes.
            return commands;
        }
        if (!(cpuStatus.coreControl == null || cpuStatus.coreControl.isEmpty())) {
            commands.add("chmod 0664 " + thermal_core_control);
            commands.add("echo " + cpuStatus.coreControl + " > " + thermal_core_control);
        }
        if (!(cpuStatus.vdd == null || cpuStatus.vdd.isEmpty())) {
            commands.add("chmod 0664 " + thermal_vdd_restriction);
            commands.add("echo " + cpuStatus.vdd + " > " + thermal_vdd_restriction);
        }
        if (!(cpuStatus.msmThermal == null || cpuStatus.msmThermal.isEmpty())) {
            commands.add("chmod 0664 " + thermal_parameters);
            commands.add("echo " + cpuStatus.msmThermal + " > " + thermal_parameters);
        }
        return commands;
    }
}
