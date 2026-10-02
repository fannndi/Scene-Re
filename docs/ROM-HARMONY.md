# ROM harmony — Scene ↔ MIUI 14 (surya) ↔ farewell-ext kernel

Single-device contract: the app is the bridge between the ROM
(`miui_SURYAIDGlobal_V14.0.2.0`, Android 12) and the kernel
(`farewell-ext_surya`, vanilla branch). This document records who owns which
node, how ownership is handed over, and how the three layers are verified.

## Actors

| Layer | Actor | Writes | Lifecycle |
|---|---|---|---|
| ROM | `init.qcom.post_boot.sh` (service `qcom-post-boot`, root, oneshot) | governor/min/max/hispeed, input + power-key boost, core_ctl, WALT, cpusets, `sched_load_boost`, LMK minfree, swappiness/read-ahead/zram, bus-dcvs | once, at `sys.boot_completed=1` |
| ROM | `mi_thermald` (`class core`, `on boot`) | `thermal_message`, cooling devices (GPU devfreq, backlight, battery) | continuous while engine OFF |
| ROM | `perf-hal-2-2` + `perfservice` (`powerhint.xml`, `perfboostsconfig.xml`) | transient boosts: CPUBOOST min/max, SCHEDBOOST, sched up/downmigrate, hispeed, SLB | event-driven |
| ROM | `miuibooster` (oneshot root) | MIUI boost socket | once |
| ROM | `millet_monitor` SIG/BINDER/PKG | cgroup `frozen`/`unfrozen`, process monitor | continuous |
| ROM | `lmkd` (critical) | userspace kills | continuous |
| App | profile engine (init/profile/release) | see table below | engine ON / apply |
| App | `ThermalService` (guard) | `scaling_max` (lower-only) + GPU `max/default_pwrlevel` (slower-only) | engine ON |
| App | `DaemonController` | `stop/start mi_thermald`, `miuibooster` | engine ON/OFF |
| Kernel | thermal core + `cpu_cooling` patch (`USE_LMH_DEV=0`, `CPUFREQ_THERMAL` notifier) | clips `scaling_max` after every policy write; trips (CPU 110 °C), kgsl `thermal_pwrlevel` | continuous (safety net) |

## Boot order

1. ROM init writes its boot baseline (`init.target.rc`, boot cpusets).
2. `mi_thermald`, perf HAL, `millet_monitor`, `lmkd` start.
3. `sys.boot_completed=1` → `qcom-post-boot` starts (root, late_start).
4. App `BootWorker` (BOOT_COMPLETED + 2 s, or 25 s with start-delay):
   `RomBootGate.awaitPostBoot()` waits until `init.svc.qcom-post-boot` is
   `stopped` (bounded 45 s), then applies init + saved mode, then marks the
   boot and schedules `PostApplyDriftGuard`.
5. `PostApplyDriftGuard`: 30 s after the apply it re-reads a node
   fingerprint; when anything rewrote values in between it re-applies once
   and records the result (Diagnostics ▸ ROM harmony).

If MIUI blocks the boot receiver (autostart/PowerKeeper), the next Home open
shows a warning banner ("Profile was not applied after boot…") and
Diagnostics reports `boot apply … MISSING`.

## Stock snapshot (engine OFF = real stock)

`StockSnapshot` captures every node the app may write **once per boot, before
the first engine write** (after the ROM post-boot gate), stores it in
`scene_stock` prefs and dumps a pullable copy to
`/sdcard/Scene/debug/stock-snapshot.json`.

`ProfileController.release()` (engine OFF):

1. stops the guard and hands thermal back to `mi_thermald`
   (`DaemonController.ensureOff`),
2. applies the snapshot as a plan (raw node writes; includes the
   `msm_performance` lock release),
3. falls back to the static `release` profile in `tuning.json` when no
   snapshot exists yet (fresh install / first boot).

The shipped `release` profile mirrors the ROM's moorea block
(`soc_id 365/366`, post_boot lines 3703–3823) — see
`TuningJsonTest.release mirrors the ROM post_boot stock`. The ROM's raw
`hispeed 1324600` is not an OPP; the nearest real OPP `1324800` is used.

## Ownership & coexistence notes

- **Thermal**: while the engine is ON, `mi_thermald` is stopped and the Scene
  guard owns CPU max **and** the GPU soft cap (mirrors mi_thermald's
  `thermal-devfreq-0` cooling: slower `max/default_pwrlevel` while warm/hot).
  Kernel trips remain the final safety net. Engine OFF → `start mi_thermald`
  restores the ROM's own scheme (sconfig mailboxes are then live again).
- **sconfig**: profiles pin `thermal_sconfig`; the snapshot restores the
  user's MIUI thermal choice (ActivityMiuiThermal) on engine OFF.
- **Perf HAL**: hints are transient (timeouts) and intentionally coexist;
  Home shows the strongest active lock (`Boost … / Cap …`) and Diagnostics
  lists `msm_performance` locks. Every apply releases stale locks first.
- **Freeze**: `millet_monitor` freezes via cgroup; Scene's freeze is
  accessibility-based. They can both act on the same app — Scene never
  changes `cgroup.freeze`.
- **`lmkd` + kernel LMK**: MIUI runs both; `lmk.minfree` tunes the same knob
  the ROM's post_boot writes, and the 6 GB stock series matches
  `release`/`balance`.
- **Sysctl packs** (`net.*`, `kernel.sched_*`, `vm.stat_interval`, block
  `queue/*`, `sched_lib`): app-owned while the engine is ON via `init`, and the
  per-boot `StockSnapshot` restores the exact pre-engine values on OFF —
  including the kernel-detected devfreq latency domains. MIUI does not manage
  these at runtime.
- **Battery saver → powersave**: a runtime overlay (not a mode change).
  Explicit mode actions (UI/tile/app-switch) end it; the base mode is kept in
  `GLOBAL_SPF_LAST_MODE` while the overlay runs so a reboot re-derives it.
- **DND while an app-mode is active**: needs notification policy access; the
  previous interruption filter is restored on leave / engine OFF / TRUE OFF
  (own-change exception, same as unfreeze).
- **Charging**: read-only by default (rule 12). The two guarded exceptions
  are `ChargeStockRestorer` (boot, legacy artifacts) and the **opt-in**
  `BypassCharging` (threshold charge pause; reset on engine OFF / TRUE OFF /
  cleanup / uninstall guard). Node evidence (extracted ROM, see
  `docs/STOCK-ROM.md`): MIUI's own `mishow.sh` uses
  `battery/input_suspend`, both `input_suspend` and `battery_charging_enabled`
  are 0777, and no ROM binary writes `battery_charging_enabled` — Scene's
  true-bypass choice cannot fight a daemon. `init.qti.chg_policy.sh` and the
  kernel own every other charge parameter.
- **Refresh rate / downscale**: per-app display overrides
  (`RefreshRateController`, `DownscaleController`). Both restore their own
  change on leave / engine OFF / TRUE OFF; the platform downscale is
  journal-mirrored so the uninstall guard disables it too.
- **zRAM/swap**: never touched by Scene (hard rule); the ROM's
  `configure_zram_parameters` + `enable_swap` own them.
- **MAC**: the ROM's `nv_mac` service rewrites the MAC at boot; the Scene MAC
  changer is runtime-only and must be re-applied after a reboot.
- **User tuning copy**: `/sdcard/Scene/profiles/<platform>.tuning.json` wins
  over the asset. After an app update, an old user copy keeps old `init`
  values (by design); the snapshot still restores real stock on OFF.

## Verification

```bash
bash tools/rom-stock-check.sh          # engine OFF: all nodes == ROM stock?
bash tools/rom-stock-check.sh --snapshot  # compare against the app snapshot
bash tools/node-watch.sh 5             # print managed nodes every 5 s
```

Diagnostics ▸ *ROM harmony* shows: post-boot wait, daemon/guard state, stock
snapshot age, last drift check, boot-apply evidence, perf locks, tuning copy
state.
