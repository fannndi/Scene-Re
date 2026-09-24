# AGENTS.md — Scene

## What this project is

Scene is a rooted Android performance/gaming app (Kotlin/Java) focused exclusively on **Xiaomi phones with Qualcomm Snapdragon SoCs**. Root-only: **no Xposed, Zygisk, LSPosed, or vaddin** integration.

## Hard scope rules

Do **not** reintroduce:

- Non-Qualcomm SoC support (MediaTek/MTK, Exynos, Kirin, Mali GPU paths, `/proc/ppm`, GED)
- Non-Xiaomi brand feature pages (OPPO, Flyme/Meizu, vivo, Samsung-specific UI)
- Xposed / Zygisk / LSPosed / vaddin integration in any form (`com.omarea.xposed`, `xposed_init`, XposedBridge API, `SceneFreezeProvider`/`SceneUnfreezeProvider` launcher hooks, `XposedExtension`/vaddin AIDL, `zygisk_*`). These were fully removed; the root path must stay framework-independent.
- Device spoof templates / model modification (`DialogAddinModifyDevice`, `device_templates`)
- MAC address spoofing (`DialogCustomMAC`, `change_mac_*`, `GLOBAL_SPF_MAC*`)
- MIUI thermal editor & configs (`ActivityMiuiThermal`, `mi-thermal-config/`, `thermal_conf3`, `MiuiThermalAESUtil`, `ThermalCheckThread`)
- Magisk module browser (`ActivityModules`, `MagiskModulesRepo`)
- Cosmetic / non-performance features. The app is a battery + SoC + gaming toolkit; UI gimmicks and unrelated utilities were removed and must not come back: auto-click & ad-skip, MIUI navbar / one-handed / edge touch, display colour & animation tweaks, launcher / live wallpaper pickers, notch hiding, camera lab & camera HAL toggles, haptic & AI-key / 377-key remapping, WiFi password viewer, DPI modifier, net checker & NTP pickers, the TWRP/OTA image page, Self-Rescue (`resurgence` module) and the `scene_freezer` Freeze List page.

## Root backend policy

The app must **not require Magisk**. `common/src/main/java/com/omarea/common/shared/RootBackend.java`
is the single write layer; it resolves one of two interchangeable backends at runtime
(`RootBackend.backend()`):

1. `OVERLAY` — writes are mirrored into an overlay directory that boot re-mounts
   (`$MAGISK_MODULE` when the environment exports it, else `/data/adb/scene/overlay`,
   `/data/adb/modules`, `/data/adb/modules_update`). Works with Magisk, KernelSU, APatch.
   Non-destructive, survives OTA.
2. `DIRECT` — remount the partition and write in place, snapshotting a one-time `.scene.bak`.
   This is the path that makes the app work with plain `su` and **no module framework at all**.

Rules that follow from this:

- Gate on `RootBackend.supported()` (any backend) or `RootBackend.overlayReady()` /
  `RootBackend.isOverlayActive()` (overlay specifically). `supported()` and `overlayReady()`
  are independent conditions — never conflate "root works" with "an overlay exists".
- Never shell out to `magisk -V`; the modern Magisk CLI removed it.
- Never reference `imgtool` or `magisk.img`; that scheme died with Magisk 18.
- Scripts get `ROOT_BACKEND` (`overlay|direct|none`) and `ROOT_MANAGER` in the environment,
  plus `OVERLAY_PATH` (legacy alias: `MAGISK_PATH`; empty when no overlay). Use the helpers in
  `kr-script/common/mount.sh` (`write_target_for`, `write_backend_available`) and
  `kr-script/common/overlay.sh` instead of branching on paths yourself.

Safe to keep: `RootBackend` and the overlay/direct kr-script helpers, AOSP-generic kr-script pages, Qualcomm + MIUI/HyperOS features, Qualcomm `ThermalControlUtils`, `ThermalDisguise` extreme-performance toggle.

## Profile options layer

`com.omarea.scene_mode.ProfileOptions` applies a tuning layer on top of the powercfg
profile scripts, driven by `addin/scene_profile_options.sh`:

- Frequency limiter (%) — caps `scaling_max_freq` at the nearest supported frequency and
  snapshots the stock min/max in `vtools.scene.freq.bak.*` props so it can be undone.
- Lite mode — undoes the min-frequency pinning of the performance profiles.
- Governor / I/O scheduler preference, applied only when the kernel advertises them.
- Game PID priority (`renice -20` + `ionice` RT) and game preload
  (`addin/game_preload.sh`, page-cache warm-up with a per-file budget).
- DND while gaming (previous zen mode saved in `GLOBAL_SPF_DND_BACKUP`).
- Bypass charging while gaming via `BypassCharge` (node table + current-drop probe).
- Optional TCP/VM/IO extras.

Bypass charging is also reachable from the charge screen (`BypassCharge.detect`) and a QS
tile (`BypassChargeTileService`). Thermal PID (`ThermalPid`) drives the generic
`/sys/class/thermal/cooling_device*` nodes and runs inside the accessibility service.
`BootGuard` reverts boot-affecting tweaks on the second boot without a confirmed UI.

## Layout

- `app/` — main app; assets in `app/src/main/assets/`
  - `powercfg/<platform>/` — per-SoC profiles; platform dir name must match `ro.board.platform` (qcom only: kona, lahaina, taro, sdm*, sm*, msm*, lito, universal…)
  - `kr-script/` — script pages; menu root is `kr-script/more.xml` (wired via `kr-script.conf`)
  - UI: `app/src/main/java/com/omarea/vtools/`
- `common/` — shell/root helpers (`KeepShellPublic`, `KernelProrp`, …)
- `krscript/` — script engine module
- `others/` — scratch/dev files only

## Conventions

- Device/SoC detection: `PlatformUtils.getCPUName()` reads `ro.board.platform`; Xiaomi check via `Build.MANUFACTURER == "XIAOMI"`.
- GPU is Adreno/kgsl only (`GpuUtils`); `supported()` = Adreno.
- Strings in `app/src/main/res/values/strings.xml`; arrays (powercfg app lists, device templates) in `configs.xml`.
- Shell scripts run through kr-script executor; page visibility via `visible="run common/*.sh"`.
- JSON under `powercfg/` must stay valid (validate with `ConvertFrom-Json` or `jq`); UTF-8 without BOM.
- profile.json `platform` field must equal the directory name.

## Build & verify

```powershell
# keystore.properties must exist (copy from keystore.properties.example) or Gradle config fails
./gradlew assembleDebug
```

Before finishing a change:

1. `./gradlew assembleDebug` succeeds (catches broken viewBinding IDs and missing strings).
2. `./gradlew testDebugUnitTest :common:testDebugUnitTest` passes.
3. Grep for regressions: `xposed|vaddin|zygisk|exynos|isMTK|/proc/ppm|kr_flyme|kr_mtk|kr_oppo|kr_vivo|ActivityMiuiThermal|DialogCustomMAC|DialogAddinModifyDevice|ActivityModules|device_templates` should only hit historical docs if anything.
4. Grep for removed root constructs: `magisk -V|imgtool|magisk\.img|magisk_merge` must not appear in live code.
5. On a connected device, `scripts/scene-adb doctor` should report a non-`none` write backend.
6. Do not edit `.gitignore`-tracked secrets; `keystore.properties` and `*.keystore` stay untracked.

## Key files

- Mode switching: `app/src/main/java/com/omarea/scene_mode/ModeSwitcher.kt`, `CpuConfigInstaller.kt`
- CPU/GPU control: `library/shell/CpuFrequencyUtils.java`, `GpuUtils.java`, `activities/ActivityCpuControl.kt`
- Kr-script menu: `assets/kr-script/more.xml` + `assets/kr-script.conf`
- Freeze (suspend-only): `activities/ActivityFreezeApps.kt` (no Xposed path)
- Misc add-ins: `activities/ActivityAddin.kt` (no model spoof / no MAC)
