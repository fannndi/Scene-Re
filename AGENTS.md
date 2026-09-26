# AGENTS.md — Scene

## What this project is

Scene is a rooted Android performance/gaming app (Kotlin/Java) focused exclusively on **Xiaomi phones with Qualcomm Snapdragon SoCs**. Root-only: **no Xposed, Zygisk, LSPosed, or vaddin** integration.

**Personal priority:** this is a private build for one person and one device —
POCO X3 NFC ("surya", SM7150-AC / SD732G) running **MIUI 14 on Android 12**. Target
that configuration first in every decision (defaults, tuning, UI); Android 10 / 11
stock MIUI and Android 13 AOSP community ROMs are best-effort secondary targets, and
anything beyond them is out of scope. Prefer a change that makes the daily driving
experience on MIUI 14 (Android 12) better over generic breadth.

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

## Android version support

Install floor is API 29 (Android 10), target API 33 (Android 13); compileSdk 34 is a
build-time ceiling only. On the surya family the official Xiaomi builds (MIUI 12 / 12.5 /
13 / 14) cover Android 10, 11 and 12 — **Android 12 (MIUI 14) is the primary target** —
while Android 13 exists only as AOSP-based community ROMs (there is no MIUI build on
Android 13 for these devices). The whole kernel/root layer is version-independent and runs
on every supported release; only platform APIs are gated and every gate has a fallback:

| Layer / feature | Android 10 (29) | Android 11 (30) | Android 12 / 12L (31/32) — **primary** | Android 13 (33) AOSP only |
| --- | --- | --- | --- | --- |
| powercfg profiles, options layer, per-game profiles, monitor | yes | yes | yes | yes |
| Game Mode API (`cmd game mode`) | - | - | yes | yes |
| Game overlay controls (`cmd game set --downscale/--fps`) | - | - | - | yes |
| Exact alarms (timed tasks) | exact | exact | permission-gated, falls back to inexact | permission-gated, falls back to inexact |
| Notifications | channel required (26+) | channel | channel | + `POST_NOTIFICATIONS` requested on first start |
| Package visibility | all packages | `QUERY_ALL_PACKAGES` declared | same | same |
| Scoped storage | `requestLegacyExternalStorage` | `MANAGE_EXTERNAL_STORAGE` via root appops, or the root shell | same | same |
| PendingIntent immutability | not required | not required | `FLAG_IMMUTABLE` on every PendingIntent | same |
| App overlay / background activity starts | overlay permission | same | same | same |

`PlatformCapabilities` (`com.omarea.utils`) is the runtime source of truth for these gates;
it renders in the Kernel features dialog and as `platform-support.txt` in the Diagnostics
bundle. The option scripts receive `SCENE_SDK` and gate `cmd game` themselves. The game
options dialog marks the downscale / target-FPS rows as Android 13+ only.

Android 13 specifics (AOSP community ROMs): a sideloaded install must unlock "Restricted
settings" on the app info screen before the accessibility service can be enabled (the
service notice explains this), and `POST_NOTIFICATIONS` is requested on first start.

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
  `ROOT_MANAGER` resolves through `RootBackend.manager()` (`magisk | kernelsu | apatch | unknown
  | none`, probed from PATH then the manager's data dir; `apd` counts as `apatch`, which covers
  FolkPatch-Re, an APatch fork whose `apd` doubles as the `su` entry point). It used to be
  exported unsubstituted, so scripts reading it got the literal `{ROOT_MANAGER}`. The persistent
  root shell reconnects and retries a command once when the shell dies (su killed / KPM
  restart), because a dead shell used to return an empty string as if the command printed
  nothing.

APatch/FolkPatch root model (verified on the surya MIUI 14 device, `me.yuki.folk` manager):
`su` is a hardlink to toybox carrying no label of its own, and the KernelPatch hook elevates the
caller by path name. Two userspace conditions gate it, both in `/data/adb/ap/package_config`
(`pkg,exclude,allow,uid,to_uid,sctx`): the row's `uid` must equal the install's current uid (a
reinstall changes it; `apd uid-listener` re-syncs and the kernel caches the synced list until the
daemon restarts), and `sctx` must name a domain the *running* policy defines. `u:r:magisk:s0`
does not exist until the Magisk rules are loaded — `apd sepolicy --magisk --live` is what makes
it valid (the store manager's default row alone gives `EACCES`/`EPIPE` on every app `su`). When
`su` works its context comes from that row; `u:r:shell:s0` is what a raw `adb shell` gets because
shell has no row. `Selinux.report()` prints all of this (`package entry`, `context entry`,
`uid listener`) so a broken root is diagnosed instead of guessed. Note the direct backend cannot
work on shared-block ext4 images (`EXT4-fs: couldn't mount RDWR because of unsupported optional
features (4000)` on `/`): the system partition is read-only by construction, so such devices stay
on overlay or none.

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
(`addin/game_list_default.txt`, from Encore Tweaks + AZenith, 534 packages), the MIUI game
list and `/data/adb/scene/games.txt`, then materialises `/data/adb/scene/games_effective.txt`
for the fallback monitor. The MIUI source queries `content://com.xiaomi.Joyose.providergame_info`
(root, read-only) and harvests every package-like token from the output, so the system's own
curated list is used as-is on MIUI 12-14 and the query is a harmless no-op on AOSP ROMs. A
`!package` line excludes a bundled or MIUI entry. The provider is MIUI's
`com.xiaomi.joyose.smartop.provider.GameInfoProvider` (DB `GameInfo.db`, columns
`pkg`/`name`/`Mode`/`enable`/`fps`/`gamemode`); Joyose also sets the per-game display refresh
rate, so MIUI and Scene's own per-game refresh override can both write it (last writer wins).
The Diagnostics bundle carries a `miu-integration.txt` probe of that provider, the
PowerKeeper feature table and the Game Turbo settings keys, so the integration can be kept
in sync with what the running MIUI build exposes. `BootWorker` runs `fstrim /data` once per
healthy boot.

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
- Per-game profiles: `GameProfileStore` resolves every game through a user override
  (`/data/adb/scene/game_profiles.txt`, `package=profile`), the learned class
  (`/data/adb/scene/game_profiles_learned.txt`, written by `GameProfiler`) and the safe
  default (Performance). Automatic maps a heavy game to Performance and a light one to
  Custom when a configuration was saved from CPU Control, otherwise to the bundled `light`
  profile (both powercfg providers have the action: no performance scheduler, no input/UFS
  boost). `GameProfiler` classifies from the kgsl GPU busy percentage plus the measured FPS
  over 45 s windows (GPU < 35 % with frames = light, GPU >= 60 % = heavy, in between stays
  undecided) and only reports after two consecutive windows agree, so a loading screen
  cannot downgrade a heavy game; the accessibility tracker and the app_process monitor share
  the classifier. The monitor idles on a 15 s ownership poll while the accessibility service
  runs and only probes the foreground every 3 s while it owns the switching.
  While a light game runs the options layer applies the light CPU/GPU caps (defaults 70 % /
  60 %) through their own `vtools.scene.light.*` apply/restore layer, so they only tighten
  the profile and the per-mode caps come back untouched on release. The effective map is
  materialised into `/data/adb/scene/game_profiles_effective.txt` and
  `vtools.scene.custom.ready` / `vtools.scene.light.ready` for the   monitor; the pre-game mode
  survives a service or monitor restart through `vtools.scene.game.backup`. A heavier game
  also gets the MIUI-style DDR latency floor (`scene_qualcomm_boost.sh`, mid OPP while the
  game runs).
- Per-game extras: the app options dialog also offers a **refresh rate** override per game
  (`DisplayModes`, SurfaceFlinger 1035 with the entry mode snapshotted in
  `vtools.scene.refresh.*` and restored on exit) and the tracker's **FPS safety valve**
  reclassifies a "light" game as heavy when it cannot hold frames for 30 s while the light
  caps are active, so a CPU-bound game (emulators) never stays capped. MIUI's own Game
  Turbo/Joyose perflocks go through the QTI perf HAL; `set_cpu_freq` clears the
  `msm_performance` userspace locks, so Scene's profile wins and the 60 s watchdog
  re-asserts it if MIUI applies a lock later.
- Game migration tuning (MIUI perfboostsconfig Type-4 `config_gameBoost`): while a game runs
  on a performance-like profile the options script writes `sched_group_downmigrate=95` /
  `sched_group_upmigrate=100` (snapshotted, restored when the game leaves or the layer
  resets), matching MIUI's own boost values from the ROM's `perfboostsconfig.xml`. The
  kernel side of the QTI perf framework is exactly `msm_performance/parameters/cpu_min_freq`
  and `cpu_max_freq` (checked against the NOS 13 surya kernel source), so the sysfs layer is
  already the complete channel — no perf-HAL dependency is needed.
- QTI perf hints (experimental, off by default): `QtiPerfHints` reflectively loads the
  framework's hidden `android.util.BoostFramework` and sends the vendor game-boost hint
  (0x1081, Type 4) on game start, with a one-time `VMRuntime.setHiddenApiExemptions` retry
  when hidden-API enforcement blocks the class. The probe result renders in
  `miu-integration.txt`; failures are logged and never affect the sysfs tuning.
- MIUI 14 thermal stack (audited from the stock `boot.img`, its embedded kernel config and
  the vendor image): the live daemon is Xiaomi's `mi_thermald` (started by
  `vendor/etc/init/hw/init.target.rc`; the `thermal-engine` service there is commented out).
  It parses AES-128-CBC encrypted `/vendor/etc/thermal-*.conf` (key/IV `thermalopenssl.h`;
  `thermal-chg-only.conf` is the one plain file), watches `/data/vendor/thermal/config/`
  for plain overrides with inotify (these win over `/vendor/etc`), keeps the active mode in
  `/data/vendor/thermal/thermal-global-mode` (key into `/vendor/etc/thermal-map.conf`:
  0 normal, 8 phone, 9/13/16 tgame, 10 nolimits, 12 camera, 15 arvr) and logs its computed
  targets to `/data/vendor/thermal/thermal.dump`. The Xiaomi `thermal_message` driver is
  present (`sconfig`, `temp_state`, `board_sensor(_temp)`, `cpu_limits`, `boost`,
  `screen_state`, per the kernel strings) and `ThermalDisguise` uses `board_sensor_temp`.
  The legacy Qualcomm KTM module `msm_thermal` is **absent from the surya kernels** (no
  `CONFIG_MSM_THERMAL` in the stock config), so `ThermalControlUtils` caches the node probe
  and every write is skipped when the module is missing; the MIUI 12 `migt` module is gone
  as well and `ThermalDisguise` only touches `glk_maxfreq` when it exists. Set on surya:
  `CONFIG_CPU_BOOST`, `CONFIG_MSM_PERFORMANCE`, `CONFIG_QTI_THERMAL_LIMITS_DCVS`,
  `CONFIG_DEVFREQ_THERMAL`, the BCL drivers and `THERMAL_GOV_STEP_WISE/USER_SPACE` — while
  `apply_thermal_policies` now only forces `step_wise` on zones whose `available_policies`
  advertise it. MIUI's own game side (`vendor/lib64/libgameoptfeature.so`, linked against
  `libthermalfeature`/`libthermalclient` and the QTI perf client) tunes the same
  `sched_group_*migrate` nodes, the Game Turbo cpusets and the `VENDOR_HINT_*` perf hints,
  which is what the options layer and `QtiPerfHints` mirror. `kernel_probe.sh` and the
  Diagnostics `miu-integration.txt` report all of this read-only.
- MIUI thermal control (device-specific, surya): the options layer drives MIUI's own knobs while
  a game runs — `SCENE_MIUI_THERMAL_MODE` writes `/data/vendor/thermal/thermal-global-mode` plus
  the `thermal_message/sconfig` mirror to one of the shipped configs (0 normal, 8 phone, 9/13/16
  tgame, 10 nolimits), validated against the config file actually existing and snapshotted in
  `vtools.scene.miui.mode.*` so the previous mode returns with the game; `SCENE_CPU_BOOST` raises
  the kernel `cpu_boost` input window to each cluster's top supported OPP through the shared
  tunable backup. `MiuThermal` exposes the read-only state (`temp_state`, global mode, QTI engine
  activity/config, runtime config dir) for the dialogs and Diagnostics. Every value has a per-game
  override in the app-options dialog (`AppOptionsStore.Override.cpuBoost/miuiThermal/miuiRefresh`),
  and MIUI's own Joyose target FPS becomes the default per-game refresh rate when the user has not
  picked a display mode (`ProfileOptions.gameRefreshTarget`). The thermal guard treats
  `temp_state >= 4` as a hot signal and releases at `<= 2` (`GameSessionTracker`), and sessions
  record `maxTempState`.
- Governors per profile (surya): the profile scripts select the CPU governor with
  `set_cpu_governor`, which only ever writes what `scaling_available_governors` advertises. The
  kernel on the device ships `schedutil`/`performance`/`powersave`/`userspace` (no
  `ondemand`/`conservative`/`interactive`); stock MIUI 14 also has `conservative`, so the chains
  start with it there and fall through to schedutil. Mapping: **powersave** -> `conservative`
  (fallback `schedutil`, then `powersave`) plus the endurance tunables, **balance** ->
  `schedutil` (daily), **performance** -> `performance` (fallback `schedutil`) with the profile
  caps bounding the heat, **light**/**fast** -> `schedutil`. The GPU uses the same
  advertised-only rule (`set_gpu_governor`: `msm-adreno-tz-v2`/`msm-adreno-tz`/`simple_ondemand`).
  The **Custom** profile owns the user's preferences: `GLOBAL_SPF_PROFILE_GOVERNOR`,
  `GLOBAL_SPF_PROFILE_GPU_GOVERNOR` and `GLOBAL_SPF_PROFILE_IOSCHED` are sent to the script only
  when the mode is `fast` and as empty strings otherwise, so the three main profiles always win
  and the restore path puts the stock values back. Availability checkers
  (`vtools.scene.gov.blocked`, `vtools.scene.gpu.gov.blocked`, `vtools.scene.io.blocked`) report
  when a selection could not be applied, e.g. "schedutil unavailable" on a kernel without it.
- Battery efficiency (the counterweight to the gaming side): the options layer applies
  `SCENE_BATTERY_ECO` while no game runs on a frugal profile (powersave/balance): it clears
  `cpu_boost/sched_boost_on_input`, drops the little-cluster `coloc_fmin` floor to 0, keeps the
  UFS link power saving (clock scaling + Hibern8) on, and batches writeback wakeups
  (`dirty_writeback/expire_centisecs` = 30 s; the kernel default is 5 s and the surya config
  leaves `CONFIG_WQ_POWER_EFFICIENT_DEFAULT` off). Everything rides the tunable snapshot layer
  and is released by games and by any performance-like profile. `PowerReport` (Diagnostics
  `power-report.txt`) prints the idle story read-only: suspend_stats, the top wakeup sources,
  `cpu_boost`/`msm_performance` state, governors, zram/swap, the irqbalance service and the
  block/power tunables. Scenario roles: **powersave = endurance** (a whole day without a
  charger: low caps, slow schedutil ramps, no input boost, big cores allowed to power collapse),
  **balance = daily** (social media and communication: a short little-cluster input boost for
  smooth taps), **performance = gaming**, **custom = the user's saved CPU Control config**, and
  the screen-off sleep mode (default powersave) keeps the day-long case switched to the frugal
  profile while the display is off.
- Stock ROM exploitation (second audit pass): `GovernorCapabilities` reads the running kernel's
  `scaling_available_governors`/`available_governors`/scheduler lists once, the options dialog
  lists exactly those (locking the rows when the kernel exposes no choice), and the app writes
  the resolved per-scenario governor to `/data/adb/scene/gov_chains.txt`, which
  `powercfg-utils.sh` reads through `set_cpu_governor_scenario`/`set_gpu_governor_scenario` (the
  built-in defaults are only the cold-start fallback; `reset_basic_governor` uses the same path).
  `StockPlatform` (Diagnostics `stock-platform.txt`) reports the rest of the platform config the
  app follows: `vendor/etc/lm/GameOptimizationFeature.xml` (the in-game DDR floor values, which
  `scene_qualcomm_boost.sh` now re-reads at runtime instead of trusting constants),
  `vendor/etc/perf/targetconfig.xml` (`CpufreqGov=1` => schedutil, `CoreCtlCpu=0`,
  `MinCoreOnline=0` - the endurance profile therefore enables core_ctl on the little cluster),
  `perfconfigstore.xml`, `system/system/etc/perfinit.conf` (zram per RAM tier, swappiness, extm,
  dex2oat budgets) and the power props (`vendor.power.pasr.enabled`, `ro.charger.enable_suspend`,
  `dalvik.vm.dexopt.thermal-cutoff`, `ro.lmk.*`). The stock `msm_irqbalance` binary and confs
  ship with all three services `disabled`: the `GLOBAL_SPF_PROFILE_IRQ_BALANCE` opt-in
  (`SCENE_IRQBAL`) runs the stock binary with a Scene-generated conf
  (`/data/adb/scene/irqbalance.conf`, `PRIO=1,1,1,0,0,0,0,0`, the graphics/timer IRQs listed in
  `IGNORED_IRQ`) and pins `msm_drm`/`sde` to cpu0-2 and `kgsl-3d0` to cpu1-2 with snapshotted
  `smp_affinity_list` values - the IRQ numbers are discovered from `/proc/interrupts` at runtime,
  adopted from the IRQ-Balancer-Configuration module. The instance is `renice -10`'d, owned
  through `vtools.scene.irqbal.owned`, and stopped again with the toggle (only the Scene
  instance; an external one is never fought).
- SELinux (is root enough?): `Selinux` answers it from the device instead of assuming. Its
  capability self-test (`selinux.txt` in the Diagnostics bundle) proves every Scene-critical
  operation: each sysfs/proc node is read and written back with the same value, the scheduler is
  re-selected by its active name, `/data/adb/scene` gets a temp file, `/dev/block/mapper/system`
  is opened for write with zero bytes (the DIRECT/overlay remount path) and a benign property is
  set. Each row is `ok`/`denied`/`missing`, and the verdict line says whether root is sufficient
  or which operations the running domain is blocked from. Denials are read from
  `/data/misc/audit/audit.log`, then `dmesg`, then the `auditd` logcat buffer, filtered to the
  root domains (`magisk`/`su`/`ksu`/`kernel`) and converted into exact
  `allow <sdomain> <ttype> <tclass> { perms }` rules from the denial itself - no type name is
  ever guessed. The opt-in `GLOBAL_SPF_SELINUX_PATCH` switch applies those rules through whichever
  tool the root backend ships (`magiskpolicy --live`, `apd sepolicy --live` for APatch and
  FolkPatch-Re, `ksud sepolicy patch`, `supolicy`), at most every 15 minutes. The ZN-AuditPatch
  reference (a ZygiskNext `logd` hook that camouflages su/magisk audit contexts) is deliberately
  **not** adopted: Zygisk is out of scope, and Scene reports denials instead of hiding them.
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
3. Grep for regressions: `xposed|vaddin|zygisk|exynos|isMTK|/proc/ppm|kr_flyme|kr_mtk|kr_oppo|kr_vivo|ActivityMiuiThermal|DialogCustomMAC|DialogAddinModifyDevice|ActivityModules|device_templates` must hit nothing (the historical docs that used to carry these names were removed).
4. Grep for removed root constructs: `magisk -V|imgtool|magisk\.img|magisk_merge` must not appear in live code.
5. On a connected device, `scripts/scene-adb doctor` should report a non-`none` write backend.
6. Do not edit `.gitignore`-tracked secrets; `keystore.properties` and `*.keystore` stay untracked.

## Key files

- Mode switching: `app/src/main/java/com/omarea/scene_mode/ModeSwitcher.kt`, `CpuConfigInstaller.kt`
- CPU/GPU control: `library/shell/CpuFrequencyUtils.java`, `GpuUtils.java`, `activities/ActivityCpuControl.kt`
- Kr-script menu: `assets/kr-script/more.xml` + `assets/kr-script.conf`
- Freeze (suspend-only): `activities/ActivityFreezeApps.kt` (no Xposed path)
- Misc add-ins: `activities/ActivityAddin.kt` (no model spoof / no MAC)
