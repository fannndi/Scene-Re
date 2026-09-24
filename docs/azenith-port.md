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
| Bypass charging | `BypassCharge.kt` + `addin/disable_charge.sh` / `resume_charge.sh` | Node table extended with the Qualcomm/Xiaomi families; lazy current-drop probe; auto during games; QS tile |
| Thermal PID | `ThermalPid.kt` | Generic `cooling_device*/cur_state`; no vendor nodes. AZenith called it "AI"; it is a PID state machine, and this port says so |
| Boot-loop guard | `BootGuard.kt` | Second boot without a confirmed UI reverts the boot state |
| Extra TCP/VM/IO tweaks | applier script (opt-in) | `tcp_fastopen`, `page-cluster`, `stat_interval`, `iostats`, `add_random` |
| Config backup | `ConfigBackup.kt` | Zip of shared_prefs + swap.conf to `/sdcard/Download/Scene` |
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

- **AppMonitor (`app_process` foreground companion)**: Scene's event hub is the
  accessibility service; a second foreground source without a consumer would be dead
  code. Revisit only if the app must work without accessibility.
- **Per-app option overrides** (preload/DND/renderer per package): per-app *mode*
  override already exists; the extra keys need a large dialog rebuild.
- **Chipset/device database** (`socs.json`, `devices.db`): the powercfg directory per
  `ro.board.platform` already covers supported devices.
- **Selective `chmod 444` write lock**: risks fighting vendor thermal daemons and needs a
  manual restore path; not worth it in a module-less setup.
- **MTK/Mali/FPSGO paths, `resetprop`-only writes, KSU soft reboot, surfaceflinger color
  tweaks**: out of Scene's scope.

## Verification

`bash -n` on all 128 asset scripts, all resource XML parse, `assembleDebug`, and the unit
suites pass. On-device behaviour still needs a connected rooted Xiaomi/Qualcomm device.
