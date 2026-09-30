# Kernel reference — `farewell-ext_surya`

Source: `/home/fannndi/farewell-ext_surya` (Linux **4.14.180**, vendor base
`msm-4.14` + surya bring-up). Branch `vanilla`/`LTO` tip `22ad66db` (13 Aug
2026). The device currently runs a build from this lineage with a few extra
patches (see *Drift* below).

## Enabled subsystems (sdmsteppe-perf defconfig)

`SCHED_WALT`, `SCHED_CORE_CTL`, `CPU_BOOST`, `SCHEDUTIL`, `MSM_PERFORMANCE`,
`THERMAL` (+ `CPU_THERMAL`, `THERMAL_TSENS`, `QTI_THERMAL_LIMITS_DCVS`,
`QTI_BCL_*`, **`THERMAL_WRITABLE_TRIPS`**), `ANDROID_LOW_MEMORY_KILLER`,
`MEMCG`, `ZRAM` (never touched by the app).

## Node semantics (verified against source)

| Node family | Semantics |
|---|---|
| `core_ctl/*` (`kernel/sched/core_ctl.c`) | `not_preferred` takes exactly **N values = cluster size** (silver 6, gold 2). `enable`, `min_cpus`, `max_cpus`, `busy_up/down_thres`, `offline_delay_ms`, `task_thres`, `nr_prev_assist_thresh` |
| `cpu_boost/parameters` (`drivers/cpufreq/cpu-boost.c`) | `input_boost_freq` / `powerkey_input_boost_freq` are `cpu:freq` pairs; **CPUs not listed keep their previous value**. Power-key boost is independent (`do_powerkey_input_boost`, patch by fannndi) with `sched_boost_on_powerkey_input` (default on). `sched_boost_on_input` toggles HMP boost during normal input boost |
| `msm_performance/parameters/cpu_{min,max}_freq` | per-CPU lock table (`cpu:val`); reset with `4294967295` (max) / `0` (min); the setter triggers `cpufreq_update_policy()` — this is the "release perf-HAL locks" step of every apply |
| `schedutil` tunables (`kernel/sched/cpufreq_schedutil.c`) | `hispeed_freq`, `hispeed_load` (clamped to 100), `up_rate_limit_us`, `down_rate_limit_us`, `pl` |
| WALT sysctls (`kernel/sysctl.c`, `kernel/sched/walt.c`) | `sched_upmigrate`, `sched_downmigrate`, `sched_group_upmigrate/downmigrate`, `sched_boost`, `sched_walt_rotate_big_tasks`, `sched_little_cluster_coloc_fmin_khz`, `sched_latency_ns`, `sched_min_granularity_ns`, `sched_wakeup_granularity_ns` — all present |
| `thermal_message/*` (`drivers/thermal/thermal_core.c`, patch) | `sconfig` / `temp_state` are **userspace mailboxes** (kernel only stores the value; mi_thermald/MIUI are the consumers). `cpu_limits` = `echo "cpuX <khz>"` → clamps via the `thermal-cpufreq-N` cooling device (`cpu_limits_set_level`). `screen_state`, `boost`, `board_sensor(_temp)` are informational |
| `thermal_zone*` trips (`sdmmagpie-thermal.dtsi`) | per-CPU `cpu-*-step` zones trip at **110 °C** (hyst 10 °C) and drive the `CPU0…CPU7` cpufreq cooling devices — the hard safety net |
| kgsl (`drivers/gpu/msm/kgsl_pwrctrl.c`) | `min/max_pwrlevel`, `default_pwrlevel` (idle level used by the governor), `thermal_pwrlevel` (thermal clamp slot, RW 0644), `throttling` (GPU thermal mitigation toggle). Pwrlevel 0 = highest clock |
| power supply (`smb5-lib.c`, bq2597x) | `input_suspend`, `battery_charging_enabled`, `constant_charge_current_max`, `step_charging_enabled`, `system_temp_level` (charge-current thermal level, LCT), `qcom-battery/restrict_chg|restrict_cur` |
| LMK | `CONFIG_ANDROID_LOW_MEMORY_KILLER=y` → `/sys/module/lowmemorykiller/parameters/minfree` is the valid knob |
| UFS | **health-descriptor support removed by the kernel patch** → `health_descriptor/life_time_estimation_*` read empty; `clkscale_enable`, `clkgate_enable`, `hibern8_on_idle_enable` remain valid |

## Thermal architecture (why "clamped" is normal)

- `cpu_cooling.c` patch: `USE_LMH_DEV=0` (LMH cooling path disabled) and the
  thermal notifier now listens for **`CPUFREQ_THERMAL`**, which
  `cpufreq_set_policy()` raises on every policy write.
- Consequence: any `scaling_max_freq` write is immediately re-clipped by the
  active cooling state — this is the "kernel thermal holds the max lower"
  case that `VerifyPolicy` classifies as expected (never fight it).
- mi_thermald (userspace) clamps through `thermal_message` (`SS-CPU6`:
  nvm-therm ≥ 50 °C → gold 1209600 kHz); the app's `ThermalService` does the
  equivalent clamp on its own thresholds while mi_thermald is stopped.

## App-side mapping (Scene-Re)

- `ProfilePlanner`: `gpu.{min,max,default,thermal}_pwrlevel`, `gpu.throttling`,
  `input_boost.sched_boost_on_input`, `powerkey_input_boost.sched_boost_on_powerkey_input`.
- `tuning.json` ships GPU idle level + throttling per profile (idle 6 /
  throttling on for savings; idle 0 / throttling off for performance;
  release restores stock) — aligned with the RvKernel-Manager templates.
- Never touched: zRAM/swap, MIUI `thermal-*.conf`, kernel tripping points.

## Drift (kernel side, handled separately)

The installed kernel exposes two nodes **not present in this tree**:

- `sched_boost_top_app` (sysctl, currently `1`)
- `cpu_boost/parameters/sched_prefer_idle`

The app already writes `sched_boost_top_app` (profile key `boost_top_app`)
and displays `sched_prefer_idle` in diagnostics; the kernel-side
addition/sync is tracked separately by the kernel author.
