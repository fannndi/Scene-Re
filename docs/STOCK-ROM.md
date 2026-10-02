# Stock ROM reference — `miui_SURYAIDGlobal_V14.0.2.0` (Android 12)

Audit of the extracted stock firmware, narrowed to everything the tuning
engine interacts with. Verified against the live device (2026-09-30) where
possible.

## Platform identity

| Fact | Value |
|---|---|
| Device / board | `surya` (POCO X3 NFC), `ro.board.platform=sm6150` |
| SoC | **SM7150 "moorea"**, `ro.soc.model=SM7150`, soc_id **365/366** |
| post_boot block that applies | `init.qcom.post_boot.sh` case `"sm6150"` → `#Apply settings for moorea` |
| perf HAL target name | **`sdmmagpie`** (all active entries in perf/powerhint XMLs) |
| Clusters | 6 silver (cpu0-5, policy0) + 2 gold (cpu6-7, policy6) |

## Stock tuning baseline (moorea block)

| Knob | Stock value | App `tuning.json` init | Note |
|---|---|---|---|
| silver core_ctl | min 4, busy 60/40, delay 100, task_thres 8, not_preferred `0 0 0 0 1 1` | silver **off** | intentional difference |
| gold core_ctl | **off** (`cpu6/core_ctl/enable=0`) | on (85/65/20) | intentional difference |
| sched migrate | 65/71, group 85/100, walt 1 | same | ✅ |
| governor | schedutil, hispeed L 1248000 / B 1324600 | per profile | |
| sched_load_boost | -6 on cpu6/7 | same | ✅ |
| hispeed_load | 85 on cpu6 | 85 policy0 | |
| input_boost | `0:1324800`, 120 ms | all cores 1324800, 40 ms | intentional |
| powerkey boost | `4:1804800 7:2208000`, 400 ms | 0-5 1708800, 6 2208000 | intentional |
| cpuset background | 0-5 | 0-1 | intentional |
| `sched_latency_ns` / min-granularity | 10 ms (init.rc) / 2 ms wakeup | 10 ms / 2 ms | ✅ (nodes verified on device) |
| L3/ddr bwmon | io 68, zones `2288 4577 7110 9155 12298 14236` | manual via Bus DCVS | |

## Node facts (device-verified)

- `ueventd.rc`: `cpu*/cpufreq/scaling_{min,max}_freq` = `0664 system:system`.
- `thermal_message/`: `sconfig` `0664`, **`temp_state` `0666`**,
  `cpu_limits`, `boost`, `board_sensor_temp`, `power`, `screen_state`.
- `sconfig` is writable via root and holds its value (`0` after test write);
  values map via `thermal-map.conf` (see `reference/mi-thermal/`).
- All planner sched nodes exist **except** `sched_prefer_sync_wakee_to_waker`
  (removed from tuning; `sched_wakeup_granularity_ns` added instead).
- LMK: `/sys/module/lowmemorykiller/parameters/minfree` exists
  (`18432,23040,27648,96768,276480,362880`), `enable_adaptive_lmk` present;
  userspace LMK disabled (`ro.lmk.enable_userspace_lmk=false`,
  `ro.lmk.use_minfree_levels=true`, `psi_complete_stall_ms=35`).
- MIUI cpusets exist: **`game`, `gamelite`, `vr`, `background/untrustedapp`**
  (created in `init.miui.rc`, chown system, 0664).

## Daemons

- `mi_thermald`: init service `class core`, user root, started `on boot`;
  config selection via `sconfig` (thermals above). `stop`/`start` by
  `DaemonController` works as designed.
- `thermal-engine`: **service commented out in the ROM — not used.**
- `miuibooster`: oneshot socket service (`/system/xbin/miuibooster`).
- **No `perfd` binary at all** — the perf daemon is
  `vendor.qti.hardware.perf@2.2-service` (root) + `/system_ext/bin/perfservice`.
  The old systemless hook target `perfd` was dead; hooks now target:
  `perfboostsconfig.xml`, `perfconfigstore.xml`, `powerhint.xml`,
  `perfservice`, perf HAL service.
- `init.qti.chg_policy.sh` chowns `power_supply/*` to system:system.
  `battery/input_suspend` and `battery/battery_charging_enabled` are **0777
  system:system** on the running `user` build (init.target.rc "add for
  mishow") — see the charge-control section below.

## Charge control (bypass) — ROM evidence

Extracted-ROM audit (`miui_SURYAIDGlobal_V14.0.2.0`, MIO-KITCHEN tree) plus
device probe:

- `vendor/etc/init/hw/init.target.rc` ("add for mishow") chmods
  `/sys/class/power_supply/battery/input_suspend` **and**
  `battery_charging_enabled` to **0777 system:system**, unconditionally on
  this `user` build (an older "0777 only under `ro.debuggable=1`" note was
  wrong for these two nodes).
- `system/system/bin/mishow.sh` (MIUI factory/show control) disables charging
  with `input_suspend=1` and re-enables with `0`.
- `battery_charging_enabled` is referenced by **no** ROM binary (only the
  chmod) — Scene's bypass writes it without contending with any daemon.
- `vendor.xiaomi.hardware.micharge@1.0-service` (+ `-impl.so`) manages
  `/sys/class/qcom-battery/*` (`input_suspend`, `cool_mode`,
  `night_charging`, …) and only *reads*
  `battery/{charge_full,current_now,cycle_count,temp,voltage_now}`; that
  `qcom-battery` class does not exist on surya (device probe).
- Semantics used by `runtime/BypassCharging`:
  `battery_charging_enabled=0` = **true bypass** (system stays powered,
  battery idle — device-verified: `current_now` ≈ 1 mA at 100 %), while
  `input_suspend=1` = **MIUI pause** (input cut, device runs on battery).
  The `bypass_charge_mode` pref selects the node preference
  (`auto`/`bypass`/`pause`); the uninstall guard resets every candidate.

## SELinux

- Rules are delivered through the auto-provisioned APatch module
  `/data/adb/modules/scene_sepolicy/{module.prop,sepolicy.rule}`. APatch
  applies `sepolicy.rule` at post-fs-data (`apd::module: load policy: …`) and
  announces the load to the kernel AVC — this is the ONLY effective path on
  this device.
- **Do not call `magiskpolicy --apply --live` at runtime**: on this APatch
  build it is a no-op for enforcement (`runtime policy authentication
  unavailable` in apd) and it re-loads the policy *without* APatch's
  boot-time patches, which breaks direct writes until the next reboot.
- Direct-write whitelist nodes end up `rw-rw-rw-` (0666); the shell
  `set_value` helper must never downgrade them (`[ -w ] || chmod` guard).
- Verified 2026-09-30: after reboot with the module, zero app AVC denials and
  direct sysfs writes succeed (node modes 666 preserved through applies).

## Perf HAL interference map (`vendor/etc/perf/*.xml`)

Resource nodes the perf HAL may write (subset that matters for tuning):
`msm_performance/parameters/cpu_{min,max}_freq`, `sched_*` families,
cpusets, stune, `schedutil/{hispeed_freq,hispeed_load}`, GPU pwrlevels,
bwmon nodes, `cpu_boost/input_boost_freq`. The engine resets the
`msm_performance` freq locks on every profile apply — that also releases
stuck hint locks.

`perfconfigstore.xml` ships: `vendor.iop.enable_iop=0`,
`vendor.enable.prefetch=false`, `ro.lmk.enable_userspace_lmk=false`,
`vendor.perf.gestureflingboost.enable=true`.

## Thermal configs (decrypted)

See `reference/mi-thermal/` for all decrypted `thermal-*.conf` files,
the `thermal-map.conf` sconfig table, and the mi_thermald runtime notes
(`SS-CPU6` 50 °C → gold 1209600 clamp etc.).

## Misc references

- `dalvik.vm.dex2oat-cpu-set=0,1,2,3` (silver only), dex2oat threads 4/6/4
  (perfinit), `dalvik.vm.dexopt.thermal-cutoff=2`.
- `persist.sys.miui_animator_sched.bigcores=6-7`.
- `ro.hwui.*` cache sizes live in vendor/build.prop; `ro.hwui.use_vulkan=`
  (empty = off).
- zRAM/swappiness stock values are documented here for reference only —
  **the app must not touch them** (hard rule 6).
