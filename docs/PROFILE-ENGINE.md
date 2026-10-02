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

Toggle: Tuner ▸ Profile card master switch (SpfConfig `GLOBAL_SPF_PROFILE_OFF`).
TRUE OFF sits in the same card and overrides everything (no writes at all).

### OFF = real stock (per-boot snapshot)

Before the first engine write of every boot `StockSnapshot` captures every node
the app may touch (after the ROM post-boot gate below). Toggling the engine
OFF stops the guard, hands thermal back to `mi_thermald`, and restores that
snapshot — the exact pre-engine state of *this* boot — falling back to the
static `release` profile only when no snapshot exists yet. The `release`
profile mirrors the ROM's `qcom-post_boot` moorea block (enforced by
`TuningJsonTest`); see `docs/ROM-HARMONY.md`.

### Boot order

`BootWorker` waits for the ROM's `init.qcom.post-boot` oneshot
(`RomBootGate`, bounded 45 s) before applying, then `PostApplyDriftGuard`
re-reads a node fingerprint 30 s later and re-applies once when anything
rewrote values in between. Diagnostics ▸ *ROM harmony* reports the result and
warns when MIUI blocked the boot receiver for a whole boot.

### Editing profiles (CPU control)

Profile editing is a **config operation, not a live apply** — there is no
"load" step:

| State | Tuner profile row | CPU control (editor) |
|---|---|---|
| engine ON | tap = switch/apply that profile | **locked** ("turn Profile OFF to edit") |
| engine OFF | tap = open its editor | full editing of any profile (chips switch profiles) |
| TRUE OFF | tap = blocked (nothing may apply) | opens **read-only** (viewer) |

- The editor loads the **effective profile** (user copy first, per-profile
  fallback to the shipped preset), edits an in-memory draft, and writes only
  that profile object back on Save (`ProfileStore` → `ProfileDoc`, `.bak`
  backup before every write). Other profiles/keys are preserved.
- `Reset to preset` copies the shipped preset of that profile back into the
  user copy.
- Every value the editor can change exists in the planner's key set (CPU
  governor/min/max/hispeed/rate limits, input boost, GPU pwrlevels/throttling,
  cores online, cpusets, sched, thermal_sconfig, LMK, UFS) — keys the kernel
  or engine does not use are never offered (locked features are reported as
  "not supported").
- A row/profil is marked **Modified** when `ProfileDiff` finds any difference
  between the user profile and its shipped preset; the editor shows the count
  and marks each changed value with `●`.
- A user copy that predates a profile no longer kills that mode: applying
  falls back to the preset of that single profile (`TuningRepository
  .readPreset` → `ProfileController.applyMode`).

### Boot

Boot applies **one** configuration source: the profile engine
(`BootWorker` → `ModeSwitcher.applyBootState`). The legacy
`CpuConfigStorage` "apply on boot" config was removed with its editor
checkbox — it could re-tune the device while the engine was OFF and fought
the profile apply at boot.

## Boot & app start

When the engine is ON:

- **boot**: `BootWorker` → `ModeSwitcher.applyBootState()` — SELinux rules +
  init tuning + the saved mode (`GLOBAL_SPF_LAST_MODE`) + daemons. The mode
  prop `vtools.powercfg` is re-synced so Home/notification/HWUI read the
  same mode the kernel got.
- **app/Tuner open**: `ModeSwitcher.ensureReady()` — idempotent per process;
  init runs once per source/provider, then the saved mode is re-applied.

`init` and profiles overlap (`input_boost`, `sched`, `core_ctl`,
`hispeed_load`), so the active mode is always applied *after* init and wins.
init never clears the mode (the old flow applied init on every Tuner visit
and reset the mode — device-verified bug). When OFF, nothing is applied
(device boots stock).

## External script escape hatch

If `/data/powercfg.sh` exists it wins over the engine for both init (`init`
argument) and modes (`<mode>` argument). The Tuner config-author row detects it
and offers removal. Nothing in the engine touches it.

## Thermal guard coordination

The applier writes `<p0max> <p6max> <gpuMax> <gpuDefault>` to
`/data/local/tmp/scene_thermald.profile_max` on every mode apply (the legacy
shell daemon reads fields 1/2 only). The guard (`ThermalService`, loop in
`ThermalController`) only LOWERS `scaling_max` when the battery gets hot
(warm/hot/critical table with 2C hysteresis), additionally moves the GPU to
slower `max/default_pwrlevel`s (mirroring mi_thermald's devfreq cooling), and
restores the profile values when cool. It never touches `scaling_min_freq`,
cores or governors. If the service fails to start,
`DaemonController` falls back to the bundled `assets/scene_thermald.sh`.

## Parameter.sh

Auto-generated on every engine init and every Tuner open. Each block:

```
[cpu.policy0.max]  allowed: 300000..1804800 KHz
  stock       : 1804800
  powersave   : 1324800
  balance     : 1497600
  performance : 1804800
  custom      : 1804800
  release     : 1804800
```

## Efficiency tuning (`meta.tuning = "efficiency-v1"`)

Design rule: caps sit on the SoC's efficiency knees, every profile writes an
explicit `hispeed` + rate-limit set (a missing key leaves the previous
profile's value in the kernel node — observed stale `hispeed_freq`), GPU
idles deep (`default_pwrlevel 6`) with a per-profile cap, UFS stays in power
save, and battery profiles carry no input-boost bursts.

| | CPU min 0/6 | CPU max 0/6 | hispeed 0/6 | GPU floor→cap (idle level) | UFS | LMK minfree |
|---|---|---|---|---|---|---|
| powersave | 300 / 300 | 1324.8 / 1324.8 | 1017.6 / 806.4 | 180 → 267 MHz (idle 180) | save | aggressive |
| balance | 300 / 300 | 1497.6 / 1708.8 | 1248 / 1209.6 | 180 → 565 MHz (idle 180) | save | stock |
| performance | 300 / 300 | 1804.8 / 2208 | 1324.8 / 1555.2 | 267 → 800 MHz (idle 267) | save | relaxed |
| custom | 1708.8 / 1209.6 | 1804.8 / 2304 | 1708.8 / 1708.8 | 267 → 800 MHz (idle 800) | perf | - |
| release | 300 / 300 | 1804.8 / 2304 | 1804.8 / 2304 | 180 → 800 MHz (idle 180) | save | stock |

LMK values live in `lmk.minfree` (six ascending 4 KB page counts): powersave
frees memory earlier, performance keeps apps cached longer, release/balance
use the stock MIUI curve. Applied through the root-shell fallback (direct
sysfs writes are blocked below SELinux on this APatch build — see
`docs/COMPATIBILITY.md`).

GPU power levels (Adreno 618, index 0–6): `800 / 650 / 565 / 430 / 355 / 267 / 180 MHz`.
`min_pwrlevel` = deepest clock allowed (the real GPU **minimum**), `max_pwrlevel`
= most performant allowed (0 = no cap), `default_pwrlevel` = idle fallback.
CPU minimum is the lowest OPP (300 MHz) everywhere except the user's `custom`
profile, so every efficiency profile can reach the idle floor.

Measured on surya (benchmark bundle, USB charger, CPU + idle scenarios,
30 s each, before → after):

| scenario | system draw | SoC temp | throughput |
|---|---|---|---|
| idle (powersave) | 1285 → **1236 mW** (−3.7 %) | 41.2 → **40.4 °C** | equal |
| CPU-saturated (powersave) | 2307 → **2064 mW** (−10.6 %) | 58.8 → **51.7 °C** | −15.7 % |

Read honestly: light use saves energy with no work lost; a fully saturated
CPU costs throughput (−15.7 %) at −10.6 % power, i.e. per-work energy is
slightly higher — but the device runs **7 °C cooler**, so sustained sessions
throttle later. Heavy users should pick balance/performance.
