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
| `sched_lib_name` / `sched_lib_mask_force` (`kernel/sched/core.c` vendor ext) | comma-separated library names; processes loading them get max-CPU capability reporting (mask 255 = all CPUs). Present on this kernel (verified 2026-10; empty by default) |
| devfreq CPU/bus latency (`/sys/class/devfreq/soc:qcom,cpu*lat|latfloor`) | `min_freq`/`max_freq` direct children (no subdir). Domains: `cpu0/6-cpu-ddr-latfloor`, `cpu0/6-cpu-l3-lat`, `cpu0/6-cpu-llcc-lat`, `cpu0/6-llcc-ddr-lat`. `*-bw` vote domains are hwmon-governed (not touched) |
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
  `gpu.bus_split` / `gpu.force_clk_on` (performance pair 0/1, stock pair 1/0),
  `input_boost.sched_boost_on_input`, `powerkey_input_boost.sched_boost_on_powerkey_input`,
  `devfreq.latency` (bus/latency domains above), `init.sched_lib`
  (`sched_lib_name` + mask), `init.net` / `init.kernel` / `init.io`
  (Encore-derived packs, `docs/ATTRIBUTION.md`).
- `tuning.json` ships GPU idle level + throttling per profile (idle 6 /
  throttling on for savings; idle 0 / throttling off for performance;
  release restores stock) — aligned with the RvKernel-Manager templates.
- `adrenoboost` is expressible (`gpu.adrenoboost`) but **locked** on this
  kernel (attr absent) — reported, never silently skipped.
- Never touched: zRAM/swap, MIUI `thermal-*.conf`, kernel tripping points.

## Port wishlist — features the ROM/stock kernel has but this tree lacks

These are probed at runtime by the app (`KernelCompat`); when missing they are
**locked** (see `docs/COMPATIBILITY.md`). Interfaces below are exactly what
the app reads/writes, so a patch can be validated with the same probes.

### 1. `fpsgo` — frame stats table (needed for kernel-level FPS)
- Node: `/sys/kernel/fpsgo/fstb/fpsgo_status` (read-only)
- Used by: `FpsUtils.readFpsgoStatusFps()`, `SurfaceFlingerFpsUtils2`
- Format: whitespace-separated text table. A header line starts with `tid`
  and contains the column names `name` and `currentFPS`; data rows keep the
  same columns. `name` is matched against the top package name (or its last
  segment), `currentFPS` must parse as float.
- Example:
  ```
  tid name currentFPS ...
  1234 com.miui.home 60.0 ...
  ```
- Stock source: Xiaomi's fpsgo/fstb kernel driver (drivers/misc/fpsgo*).
- Fallback in app: `measured_fps` (already present).

### 2. `bus_dcvs` — Qualcomm bus DCVS domains
- Root: `/sys/devices/system/cpu/bus_dcvs/{DDR,DDRQOS,L3,LLCC}/`
- Per domain:
  - `available_frequencies` (read, kHz space-separated)
  - `boost_freq` (rw, direct child)
  - `min_freq` / `max_freq` (rw) under a subdirectory — the app reads
    `cat $domain/*/min_freq | head -1` and writes with
    `chmod 644 → echo value → chmod 444` per matching file
- Used by: `BusDcvs.kt` (Tweaks ▸ Qualcomm rows, hidden while locked).
- Note: this kernel exposes only devfreq equivalents
  (`/sys/class/devfreq/soc:qcom,cpu-*-bw`, `cpu*-l3-lat`, `memlat`).
  **Resolved 2026-10:** the app grew a devfreq backend
  (`ProfilePlanner.devfreq` + `KernelCompat.devfreq_bus`) and now tunes the
  latency domains directly, so porting the bus_dcvs driver is optional.

### 3. `ddr_fixed` — forced DDR frequency
- Write: `/dev/scene/debug/qcom_aoss/ddr_frequency_mhz` — value in **MHz**
  (`kHz / 1000`)
- Readback: `/dev/scene/ddr_frequency_mhz` — plain integer MHz
- Used by: `BusDcvs.ddrFixedSet/Read` (writes MHz then reads back).
- Example: `echo 1804 > .../ddr_frequency_mhz` (MHz), readback `1804`.

### 4. `perfmgr` — MIUI perf manager toggle
- Node: `/sys/module/perfmgr/parameters/perfmgr_enable` (rw, `0`/`1`)
- Used by: Tweaks "Perfmgr" switch (hidden while locked).

### 5. `migt` — `glk_maxfreq` (optional, lahaina-era)
- Node: `/sys/module/migt/parameters/glk_maxfreq` (rw, three ints; `0 0 0`
  lifts the GLK clamp)
- Used by: `ThermalDisguise` (already gated to `lahaina` CPUs, so it is
  hidden on surya; only listed for completeness).

### 6. `sched_boost_top_app` (community build has it; port so vanilla matches)
- Node: `/proc/sys/kernel/sched_boost_top_app` (rw int)
- Used by: profile key `boost_top_app` (created via `sysctl` entry
  `sched_boost_top_app`).

### 7. `cpu_boost/sched_prefer_idle` (community build has it; port to vanilla)
- Node: `/sys/module/cpu_boost/parameters/sched_prefer_idle` (rw, `0`/`1`)
- Used by: diagnostics only (not written by any profile yet).

### 8. `usb_pd/pd_allowed` — USB PD allow switch
- Node: `/sys/class/power_supply/usb/pd_allowed` (rw, `0`/`1`)
- Present on stock MIUI kernels; this kernel exposes only
  `/sys/class/power_supply/usb/pd_active` (read/trigger).
- Used by: `BatteryUtils.setAllowed()` (charge-controller PD toggle). The app
  now returns early when `pd_allowed` is missing, so the toggle is a no-op
  instead of a silent failure.
- Expected behavior: writing `0` disallows PD negotiation, `1` allows it
  (`pd_active` mirrors the negotiated state).

### Not to port
- `vm.page_cluster` — legacy sysctl removed from modern kernels; the app
  keeps the key inert and reports it as locked.
- `sched_prefer_sync_wakee_to_waker` — never existed on this kernel line;
  removed from the tuning JSON.
