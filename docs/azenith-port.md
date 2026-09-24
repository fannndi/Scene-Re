# AZenith → Scene port notes

Audit source: `C:\Users\FANNNDI\Documents\AZenith` (Magisk module, Apache-2.0).
Scene keeps its own module-less root architecture; only mechanisms were adapted.

## Ported

| AZenith | Scene | Notes |
|---|---|---|
| Per-profile kernel tuning | `addin/scene_profile_options.sh` + `ProfileOptions.kt` | Runs after every powercfg switch |
| Frequency limiter (%) | same | Nearest frequency from `scaling_available_frequencies`; stock values snapshotted to `vtools.scene.freq.bak.*` |
| Lite mode (no min-freq pin) | same | Restores the stock minimum on performance modes |
| Custom governor / I/O scheduler | same | Applied only when advertised by the kernel |
| Game PID priority | same | `renice -20` + `ionice` realtime for the game PIDs |
| DND while gaming | `ProfileOptions.updateDnd` | Previous zen mode saved and restored |
| Game preload | `addin/game_preload.sh` + `GamePreloader.kt` | Page-cache warm-up, per-file budget, arm64 → arm → apk dir fallback (AZenith hardcoded arm64) |
| Bypass charging | `BypassCharge.kt` + `addin/disable_charge.sh` / `resume_charge.sh` | Node table extended with the Qualcomm/Xiaomi families; lazy current-drop probe; auto during games (kept in sync by `BatteryReceiver` while the game runs, manual QS state left alone); QS tile |
| Thermal PID | `ThermalPid.kt` | Generic `cooling_device*/cur_state`; no vendor nodes. AZenith called it "AI"; it is a PID state machine, and this port says so |
| Boot-loop guard | `BootGuard.kt` | Second boot without a confirmed UI reverts the boot state |
| Extra TCP/VM/IO tweaks | applier script (opt-in) | `tcp_fastopen`, `page-cluster`, `stat_interval`, `iostats`, `add_random`, congestion-control preference (`bbr3→…→cubic`), `sched_lib_name` game-library boost |
| Game resolution downscale / target FPS | applier script (`cmd game`) | Android 13+ gets the overlay controls (`--downscale`, `--fps`), Android 12 the game-mode override; applied only while a game is foreground, reset when it leaves |
| Config backup | `ConfigBackup.kt` | Zip of shared_prefs + swap.conf to `/sdcard/Download/Scene` |
| Diagnostics bundle | `Diagnostics.kt` + FileProvider | Scene log + device/SoC info + root backend + config, shared as one zip |
| User game list | `GameListStore.kt` | Text file under `/data/adb/scene`, merged with the game category; drives preload, DND, bypass and the monitor |
| Per-app option overrides | `AppOptionsStore.kt` + `DialogAppProfileOptions` | Lite/preload/DND/bypass/downscale/FPS/renderer per app, "Follow global" by default |
| Mode-switch debounce | `AppSwitchHandler.scheduleToggle` | Cancellable 1.5 s grace (5 s when the delay option is on) to avoid tunable thrashing |
| Watchdog re-apply | `ProfileWatchdog.kt` | Every 60 s re-asserts the limiter and the bypass node instead of locking nodes read-only |
| Reboot options | `DialogRebootOptions.kt` | Reboot, recovery, bootloader, framework restart, power off |
| Fallback monitor | `SystemMonitor.kt` + `MonitorManager.kt` | `app_process` companion (dumpsys foreground probe, no hidden-API bypass) that applies the game profile when accessibility is off; stands down while `vtools.scene.accessibility=1` |
| Page-cache preload | `native-lib.cpp` `preloadPath` via `SceneJNI` | vmtouch-style mmap page touch in-process, per-file budget, shell script fallback |
| Per-game renderer | `ProfileOptions.applyGameRenderer` | Sets `debug.hwui.renderer` and restarts the game when it differs; restores on exit |
| QS tile for bypass | `BypassChargeTileService.kt` | Profile tile already existed |

## AZenith bugs fixed here

- `powercfg-utils.sh` (kona/lahaina/taro): a `case` pattern separated by a comma instead
  of `|` made the whole file fail to parse, so the per-app scheduling logic never ran.
- Game preload hardcoded `lib/arm64`; this port falls back to `lib/arm` and the apk dir.
- Refresh-rate index mapping (AZenith maps sorted Hz to a SurfaceFlinger index) — Scene
  already used `mode.id` and keeps that.
- Receivers exported without permission — the new tile services are tile-bound.
- `HiddenApiBypass` dependency — not ported (third-party hidden-API bypass; out of scope).

## Deferred (with reasons)

- **Kernel tunables overlay** (`cpu/eas/enable`, `split_lock_mitigate`,
  `workqueue/power_efficient`, `sched_features`, WALT): the per-platform powercfg scripts
  own per-mode scheduler tuning; layering AZenith's values on top could regress on
  untested kernels. Revisit only with device testing.
- **Chipset/device database** (`socs.json`, `devices.db`): the powercfg directory per
  `ro.board.platform` already covers supported devices.
- **Profile timeout**: Scene switches on app change and now debounces a pending switch,
  so a time-based return-to-previous-profile is not needed.
- **MTK/Mali/FPSGO paths, `resetprop`-only writes, KSU soft reboot, surfaceflinger color
  tweaks**: out of Scene's scope.

Selective write locking was replaced by `ProfileWatchdog` (re-apply instead of lock), and
the app_process monitor, per-app overrides and per-game renderer were implemented.

## Verification

`bash -n` on all 128 asset scripts, all resource XML parse, `assembleDebug`, and the unit
suites pass. On-device behaviour still needs a connected rooted Xiaomi/Qualcomm device.
