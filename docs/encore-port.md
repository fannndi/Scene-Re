# Encore Tweaks port

Source studied: [Rem01Gaming/encore](https://github.com/Rem01Gaming/encore) (Apache-2.0).
Encore is a Magisk/KernelSU/APatch module with a C++ profiler daemon (`encored`),
a per-SoC profile script (`encore_profiler.sh`) and a Vue WebUI.

Scene already owns the same architecture natively: accessibility events instead
of the binder daemon, `powercfg` per-SoC profiles as the tuning base, the
profile options layer as the reversible add-on and the game list for detection.
This port only takes the pieces Scene was missing, adapted to a module-less
root environment on Xiaomi/Snapdragon devices.

## Ported

| Encore mechanism | Scene implementation |
|---|---|
| Snapdragon bus/DRAM profile (`/sys/class/devfreq/*memlat*`, `*latfloor*`, `*ddr-lat*`, `bus_dcvs/DDR|LLCC|L3`) | `addin/scene_qualcomm_boost.sh` behind the **Snapdragon bus/DRAM boost** toggle: max on performance/fast, mid with lite mode, unlock on balance/powersave |
| Snapdragon GPU profile (kgsl devfreq, `min/max_pwrlevel`, `bus_split`, `force_clk_on`) | Same script, **Snapdragon GPU boost** toggle. Power levels are snapshotted into `vtools.scene.gpu.pwrlevel.bak.*` and restored on unlock |
| Optional GPU low pin in powersave (Encore's `QCOM_NO_GPU_POWERSAVE` mitigation) | **GPU low pin in powersave** sub-toggle, default off because of the video glitch reports |
| perfcommon/kernel tunables (`split_lock_mitigate`, `sched_features` `NEXT_BUDDY`/`NO_TTWU_QUEUE`, `sched_nr_migrate`, granularities, `sched_child_runs_first`, `sched_autogroup_enabled`, `sched_schedstats`, `perf_cpu_time_max_percent`, `tcp_timestamps`, `compaction_proactiveness`, `nr_requests`, stune) | `apply_kernel_tunables()` in `addin/scene_profile_options.sh`, part of **Extra system tweaks**. Every node is snapshotted into `vtools.scene.tweak.bak.*` and restored when the toggle is off |
| Extended `sched_lib_name` list | `apply_sched_lib()` now merges Scene's original set with Encore's engine list |
| Battery saver -> powersave profile | `BatterySaverFollow.kt`: broadcast receiver plus `PowerManager`/`low_power` polling in the 60 s watchdog, so it also works on MIUI/HyperOS where Encore's binder callback does not fire. The previous mode is stored and restored |
| `gamelist.txt` (531 packages) | `addin/game_list_default.txt`, bundled as the baseline of `GameListStore`. A `!package` line in `/data/adb/scene/games.txt` excludes a bundled entry |
| Per-game "enable tweaks" | `AppOptionsStore.Override.enabled` with an **Apply profile options** row (Follow global / On / Off) in `DialogAppProfileOptions` |
| Device mitigation defaults (`DISABLE_DDR_TWEAK`, `NO_PERFORMANCE_CPUGOV`, `QCOM_NO_GPU_POWERSAVE`) | Expressed as granular toggles instead of a device database: bus/DRAM and GPU boost are independent switches, GPU low pin is opt-in, and the governor is a plain preference |

## Deliberately not ported

- MediaTek / Exynos / Unisoc / Tensor / Tegra profile branches - out of scope.
- Oppo/Oplus/Realme touchpanel, `opchain`, `cpufreq_bouncing`, `task_cpustats_enable`
  (only the generic guarded variant is kept), cpustats - other brands.
- Kernel panic disables, `drop_caches` on profile entry, vendor `battery_saver`
  module - unsafe or vendor specific, not worth the risk on stock MIUI/HyperOS.
- Thermal `step_wise` policy forcing - Scene owns thermal through `ThermalPid`
  and the powercfg scripts; on MIUI the vendor policy should stay untouched.
- Governor/frequency pinning per mode - already owned by the `powercfg` profiles.
- Binder daemon, inotify config reload, Magisk/KSU/APatch packaging, WebUI - the
  app already provides the equivalent through accessibility events, SharedPreferences
  and its own UI.


## Second pass (audit A-G)

| Finding | Scene implementation |
|---|---|
| Mode-aware VM/IO (Encore: vfs 80/120, read_ahead 32/128, nr_requests 32/64, stune per profile) | `apply_mode_tunables()` - values follow the active mode, everything snapshotted and restored |
| `mmc_core/use_spi_crc=0` (perfcommon) | `apply_kernel_tunables()`, guarded + snapshot |
| `battery_saver` kernel module off on performance/balance, on in powersave | `apply_battery_saver_module()`, digit/Y-N aware, snapshot |
| Governor candidates (`scx`, `schedhorizon`, `sched_pixel`, `sugov_ext`, `uag`, `schedplus`, `energy_step`, `interactive`, `conservative`) | governor spinner widened; `apply_governor` still validates against `scaling_available_governors` |
| Lite mode floors the minimum at the middle OPP instead of releasing it | `apply_lite_min_freq()`, clamped to the active cap, falls back to the stock minimum when frequencies are not advertised |
| `drop_caches 3` on profile entry | **Clear page cache on game start** toggle, runs only while a game is foreground |
| Addon status files (`current_profile`, `gameinfo`) | `/data/adb/scene/current_profile` + `/data/adb/scene/gameinfo` (`pkg pid uid` or `NULL 0 0`), written by the app and by the fallback monitor |
