# Internal benchmark (profile vs stock)

Scene ships an internal benchmark whose goal is **not** a synthetic score, but
proof that a profile beats stock on *efficiency at equal usability* — and a
data source for tuning the profiles further.

## Two modes

| Mode | What it proves | Power measured |
|---|---|---|
| **Discharge** (no charger) | lower battery drain (mAh/mWh) for the same workload | battery side (`Vbatt × Ibatt`) |
| **Charger** (arus masuk) | charging is not slowed and thermals stay sane under use | charger input (`Vusb × Iusb`) + battery charge current |

## Two ways to run

- **Manual (user)**: pick one target profile (stock / powersave / balance /
  performance), scenarios and duration, then `Run manual`. Repeat for another
  profile to compare.
- **Agent suite (AI)**: one tap runs all four profiles back-to-back with
  temperature gates, restores the original engine state at the end and stores
  a complete bundle. Stable view ids (`bench_run`, `bench_suite`, `bench_stop`,
  `bench_phase`, …) keep it drivable from UI automation.

## Scenarios

`idle`, `scroll` (list auto-scroll), `cpu` (multi-thread int/float/PI), `gpu`
(fill-rate shader), `mixed` (GPU + CPU + memory churn), `io` (4 KB buffer
write/read), `video` (bundled 720p30 H.264 clip, identical every run).

Each scenario: warmup (3–5 s, not measured) → measured window (30/60/120 s)
with 1 Hz sampling. Fast-moving parameters (CPU/GPU frequency, load, battery
current) are read **5× per tick**: the median lands in the value columns and
`*_min`/`*_max` columns record the spread — a wide spread flags a noisy tick.
Work counters (iterations / frames / pixels / bytes) allow energy-per-work
comparisons, so a profile that lowers power by throttling is visible as lower
throughput. Drain percentages divide by the kernel's `charge_full_design`
(µAh → mAh, plausibility-checked; power-profile fallback), recorded in
`meta.txt` as `design_capacity`.

**Charging is read-only**: the benchmark (and the whole app) never writes a
charge node — see `docs/ARCHITECTURE.md` invariant 13. Charger-mode
confounders (night slow charge, QC limit prefs) are only *recorded* in meta.

**TRUE OFF refuses the benchmark**: while `TrueOff.isOff` the start button is
blocked with a toast (profiles must be controllable to compare them) —
disable the TRUE OFF switch on Home first.

## Fairness controls

- screen stays on, portrait, fixed brightness (`settings put`, restored after);
- plug state must match the selected mode, otherwise the run is refused;
- suite targets start after a cool-down gate (battery temp ≤ baseline+1.5 °C,
  max 5 min) and the actual wait is recorded;
- abort on extreme temps (55 °C battery / 95 °C SoC), battery < 15 % (discharge),
  charger changes, screen off, or user stop — partial data is kept and marked;
- every run records kernel, platform, tuning source (user copy vs bundled),
  `tuning_hash` (SHA-1 prefix of the active tuning.json), sconfig, charger type
  and charge-feature flags (night slow charge / QC limit) as confounders.

## Output — per-second detail (LLM/agent friendly)

Bundle: `/sdcard/Android/data/com.omarea.vtools/files/benchmark/<stamp>-<mode>/`

```
run-<target>/samples.csv   1 row per second, real dt_ms, no sentinels
run-<target>/meta.txt      environment + confounders for this run
run-<target>/report.md     per-scenario table + frequency histograms
run-<target>/summary.json  machine-readable scenario aggregates
report.md                  suite report: battery drained per profile (Δ%),
                           per-scenario comparison, verdict vs stock
summary.json               all runs, machine-readable
```

`samples.csv` columns: `elapsed_ms, dt_ms, scenario, battery_mv, battery_ma,
battery_mw, usb_mv, usb_ma, usb_mw, usb_type, battery_temp_c, capacity_pct,
soc_temp_c, cpu0_temp_c, gpuss_temp_c, ddr_temp_c, cpu0_khz…cpu6_max_khz,
cpu_load_pct, gpu_mhz, gpu_load_pct, fps, fps_frames, fps_jank, work_units,
cpu0_khz_min/max, cpu6_khz_min/max, cpu_load_min/max, gpu_mhz_min/max,
gpu_load_min/max, battery_ma_min/max` (the `_min`/`_max` suffixes are the
5-sub-sample spread within that second).

Aggregates per scenario: avg/median/max current, avg/max power, mAh, mWh,
`% of design capacity` (AnTuTu-style), `%/hour`, avg/max temps + ΔT, avg
cluster frequencies + **time-in-frequency histogram**, GPU freq/load, FPS
avg/P95/min and jank %, work/s and mWh per 1000 work units, plus the
dt-weighted sub-sample spreads (`avgBatteryMaRange`, `avgCpuLoadRange`,
`avgGpuLoadRange`) for noise debugging.

Pull everything with:

```
adb pull /sdcard/Android/data/com.omarea.vtools/files/benchmark
```

## Notes & limitations

- `scaling_cur_freq` is the governor's last request, not a hardware readout —
  good for comparisons, not for absolute claims.
- Chargers are not constant sources: compare runs only with the same charger
  type (`usb_type`, `current_max` are recorded).
- Battery temperature at suite start still confounds the first run; the gate
  reduces but does not eliminate it.
- The video scenario plays a fixed bundled clip, so decode cost is identical
  across runs (it is not a video-quality benchmark).
