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
- Optional TCP/VM/IO extras (every touched tunable is snapshotted in
  `vtools.scene.tweak.bak.*` and restored when the option is off). Mode aware:
  `vfs_cache_pressure`, `read_ahead_kb`, `nr_requests`, stune,
  `workqueue/power_efficient`, the `battery_saver` module, `sched_features`,
  kernel `panic*` and thermal `policy=step_wise`.
- Snapdragon bus/DRAM and GPU boost (`addin/scene_qualcomm_boost.sh`, adapted from
  Encore Tweaks + AZenith): devfreq latency nodes with governor switching,
  `bus_dcvs` (DDR/DDRQOS/LLCC/L3, all three layouts), kgsl power levels and
  `adrenoboost`. Applied per mode when enabled, released otherwise.
- Addon-style toggles: Governor response tuning (WALT / schedhorizon), stop
  framework tracing, stop logger services, global HWUI renderer, clear page cache
  on game start.
- Status files for tooling: `/data/adb/scene/current_profile` and
  `/data/adb/scene/gameinfo` (pkg/pid/uid), written by the app and the monitor.
- Follow the system battery saver with the powersave profile (`BatterySaverFollow`).

Bypass charging is also reachable from the charge screen (`BypassCharge.detect`) and a QS
tile (`BypassChargeTileService`). `BypassCharge` owns the bypass node and every caller holds a
*reason* (`game` / `manual` / `protect`); the node stays bypassed while any reason is set and is
released with the last one, so the game path, the charge-protection level (`BatteryReceiver`) and
the manual toggle cannot fight. Thermal PID (`ThermalPid`) drives the generic
`/sys/class/thermal/cooling_device*` nodes and runs inside the accessibility service.
`BootGuard` reverts boot-affecting tweaks on the second boot without a confirmed UI.

Kernel capability report: `KernelCapabilities` runs `addin/kernel_probe.sh` (read-only) and caches
the `key=value` rows; the Kernel Features dialog and the Diagnostics bundle render it, and feature
code should ask `supported()` before offering a toggle.

GPU limiter + thermal guard live in the same options layer: `apply_gpu_limit` publishes the
effective cap in `vtools.scene.gpu.cap`, which `scene_qualcomm_boost.sh` clamps every GPU write to;
the guard (`vtools.scene.guard.*` props + the `SCENE_GUARD_ONLY` fast path) caps CPU/GPU while the
battery is hot and restores the user limiter / kernel values on release.

Game session report: `GameSessionTracker` (started from the Application) samples battery
level / temperature / FPS / mode while a game runs, stores summaries through `GameSessionStore`
and drives the thermal guard.

Game detection: `GameListStore` merges the bundled baseline
(`addin/game_list_default.txt`, from Encore Tweaks + AZenith, 534 packages) with
`/data/adb/scene/games.txt` and materialises `/data/adb/scene/games_effective.txt`
for the fallback monitor. A `!package` line excludes a bundled entry.
`BootWorker` runs `fstrim /data` once per healthy boot.

## Layout

- `app/` — main app; assets in `app/src/main/assets/`
  - `powercfg/sm6150/` — the only bundled profile; the directory name matches `ro.board.platform` on the target device (POCO X3 NFC "surya", Snapdragon 732G / SM7150-AC, kernel msm-4.14). Other SoC directories were removed: do not add per-platform profiles back. Keep `active.sh` / `conservative.sh` / `powercfg-base.sh` / `powercfg-utils.sh` in sync with the SD732G OPP tables and node notes documented at the top of `powercfg-utils.sh`.
- Mode set: `powersave` / `balance` / `performance` / `fast` (shown as **Custom**) / `off`.
  Custom applies the config saved from CPU Control (`CpuConfigStorage`, `cpuModeName=fast`)
  and falls back to the bundled profile; the card opens CPU Control while no config is
  saved and a long-press re-edits it. `off` runs the powercfg `off` action which restores
  the boot-stock snapshot taken once per boot in `powercfg-base.sh` (`vtools.stock.*` props)
  and keeps the options layer silent (`ProfileOptions.apply` resets once per entry).
  Automatic mode switching is game-only: `GameListStore.isGame` picks Performance and the
  pre-game mode comes back on exit. The dynamic response engine (per-app mode assignment,
  strict/delay switches, `GLOBAL_SPF_DYNAMIC_CONTROL*`) was removed.
  - `kr-script/` — script pages; menu root is `kr-script/more.xml` (wired via `kr-script.conf`)
  - UI: `app/src/main/java/com/omarea/vtools/`
  - `com.omarea.scene_mode` is split by responsibility: root holds the mode engine
    (`ModeSwitcher`, `SceneMode`, `AppSwitchHandler`, `CpuConfigInstaller`, watchdogs),
    `options/` the profile-options layer, `power/` charge/battery/thermal, `game/` game
    list / preload / sessions, `monitor/` status files + fallback monitor, `trigger/`
    timed tasks, `service/` every manifest component (receivers, tiles, notification
    listener — AndroidManifest references these by FQN, keep them in sync).
    `KernelCapabilities` sits in `com.omarea.utils` next to `Diagnostics`.
- `common/` — shell/root helpers (`KeepShellPublic`, `KernelProrp`, …)
- `krscript/` — script engine module
- `others/` — scratch/dev files only

## Conventions

- Device/SoC detection: `PlatformUtils.getCPUName()` reads `ro.board.platform`; Xiaomi check via `Build.MANUFACTURER == "XIAOMI"`.
- GPU is Adreno/kgsl only (`GpuUtils`); `supported()` = Adreno.
- Strings in `app/src/main/res/values/strings.xml`; arrays (powercfg app lists, device templates) in `configs.xml`.
- Shell scripts run through kr-script executor; page visibility via `visible="run common/*.sh"`.
- JSON under `powercfg/` must stay valid (validate with `ConvertFrom-Json` or `jq`); UTF-8 without BOM.
- The scheduler tunables follow the msm-4.14 ABI: `sched_upmigrate` / `sched_downmigrate` take one percentage (1..100, up > down) and `sched_group_*migrate` are percentages; there is no `sched_boost_top_app` node.
- powercfg / boost scripts must read parameters from the kernel instead of hardcoding: snap CPU frequencies with `snap_cpu_freq`, pick governors from `scaling_available_governors` / `available_governors`, discover UFS / devfreq / block nodes by glob, and guard every write with `write_node` / `set_value`. Verified against the surya A10 (`MiCode/Xiaomi_Kernel_OpenSource` `surya-q-oss`) and CLO A11+ (`LA.UM.9.1.r1-06700-SMxxx0.0`) trees.

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
