# Profile Engine (tuning.json)

The engine is the single source of truth for mode tuning. It is device-exact:
every value lives in a per-device JSON, nothing is hardcoded in Kotlin.

## Files

- Bundled: `app/src/main/assets/powercfg/<platform>/tuning.json`
- User copy (wins when present): `/sdcard/Scene/profiles/<platform>.tuning.json`
- Catalog (generated): `/sdcard/Scene/profiles/Parameter.sh`

`<platform>` = `PlatformUtils().getCPUName()`, e.g. `sm6150`.

## Schema

```jsonc
{
  "init": {                     // applied on engine init (app start / boot)
    "core_ctl":  { "cpu6": { "enable": 1, "min_cpus": 0 } },
    "sched":     { "upmigrate": 71, "boost_top_app": 1 },
    "sched_load_boost": { "cpu6": -6 },
    "hispeed_load":     { "policy0": 85 },
    "input_boost":        { "0": 1324800, "ms": 40 },
    "powerkey_input_boost": { "0": 1708800, "ms": 400 },
    "lpm_sleep_disabled": 0,
    "cores_online": { "6": 1 },
    "vm": { "dirty_ratio": 25, "read_ahead_kb": 256 },
    "cpuset": { "foreground": "0-7", "top-app": "0-7" }
  },
  "profiles": {                 // "fast" is a legacy alias of "custom"
    "powersave":   { "cpu": { "policy0": { "governor": "schedutil",
                                           "min": 300000, "max": 1612800,
                                           "hispeed": 1248000,
                                           "down_rate_limit_us": 0,
                                           "up_rate_limit_us": 0 } },
                     "input_boost": { "0": 0, "ms": 0 },
                     "sched":   { ... },
                     "cpuset":  { ... },
                     "core_ctl": { "cpu0": "off", "cpu6": "on" },
                     "gpu": { "min_pwrlevel": 6, "max_pwrlevel": 4 },
                     "ufs": "save",             // "save" | "perf"
                     "thermal_sconfig": 0 },
    "balance":     { ... },
    "performance": { ... },
    "custom":      { ... },
    "release":     { ... }      // stock defaults, applied when engine is OFF
  }
}
```

Optional per-profile HWUI block (consumed by `HwuiController`):

```jsonc
"hwui": { "renderer": "opengl", "vulkan": "true" }   // default = remove props
```

## Validation & verification

- Frequencies are clamped to the live `scaling_available_frequencies`
  (`DeviceCaps.clampFreq`).
- Governors are checked against `scaling_available_governors`; unknown values
  are skipped and reported as plan warnings (ShellLog + Diagnostics).
- After every apply the applier reads back governor/min/max per policy and
  retries the block once on mismatch.
- A `scaling_max_freq` that reads **lower** than requested is treated as
  kernel thermal mitigation (e.g. `thermal-cpufreq-6` cooling state while
  charging), not a failure: it is logged as info and skipped, not retried.
  The value applies on a later cool apply or when the kernel releases the
  cooling state.

## ON/OFF lifecycle

| State | kernel | daemons | HWUI |
|---|---|---|---|
| ON + mode | mode ops applied, verified | `mi_thermald`/`miuibooster` stopped, `ThermalService` running | per-app > profile > default |
| OFF (`release`) | stock profile applied | MIUI daemons restored, `ThermalService` stopped | all overrides cleared |

Toggle: Tuner ▸ profile engine switch (SpfConfig `GLOBAL_SPF_PROFILE_OFF`).

## Boot

`BootWorker` calls `ProfileController.applyBootState()` when the engine is ON:
init tuning → last mode (prop `vtools.powercfg`) → daemons. When OFF it does
nothing (device boots stock).

## External script escape hatch

If `/data/powercfg.sh` exists it wins over the engine for both init (`init`
argument) and modes (`<mode>` argument). The Tuner config-author row detects it
and offers removal. Nothing in the engine touches it.

## Thermal guard coordination

The applier writes `<p0max> <p6max>` to `/data/local/tmp/scene_thermald.profile_max`
on every mode apply. The guard (`ThermalService`, loop in `ThermalController`)
only LOWERS `scaling_max` when the battery gets hot (warm/hot/critical table
with 2C hysteresis) and restores the profile max when cool. It never touches
`scaling_min_freq`, cores or governors. If the service fails to start,
`DaemonController` falls back to the bundled `assets/scene_thermald.sh`.

## Parameter.sh

Auto-generated on every engine init and every Tuner open. Each block:

```
[cpu.policy0.max]  allowed: 300000..1804800 KHz
  stock       : 1804800
  powersave   : 1612800
  balance     : 1708800
  performance : 1804800
  custom      : 1804800
  release     : 1804800
```
