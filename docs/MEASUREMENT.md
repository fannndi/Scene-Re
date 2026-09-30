# Measurement accuracy & the parameter log

Every measured parameter in Scene follows the same rules. If you add a new
metric, follow them too.

## Principles

1. **One consistent sample.** Values that appear together must be read
   together: `util/measure/SysReader` reads all requested nodes with direct
   file reads first and **one** batched root-shell round trip for the rest.
   Per-value shell calls are banned in sampling paths (temporal skew + jitter).
2. **No sentinels.** A failed read is `null` / `valid = false`, never `1f`,
   `-1` stored as a real number, or `Long.MIN_VALUE` math.
3. **Canonical signs.** Battery current is stored/displayed as
   *positive = charging, negative = discharging* (`BatterySampler` calibrates
   polarity against the charging status; community kernels differ from stock).
4. **Real time.** Intervals/logs use `SystemClock.elapsedRealtime()`
   (monotonic); wall-clock is only a display timestamp. Samples carry real
   `dt_ms` and aggregates are dt-weighted.
5. **Validated ranges.** Reject implausible values: `|I| <= 20 A`, battery
   temp `-30..120 °C`, FPS `2..500`, thermal `-40..150 °C`.

## Parameter map

| Parameter | Source (priority) | Normalisation | Logged as |
|---|---|---|---|
| FPS | `measured_fps` → gfxinfo → fpsgo → SF counter | window span, no sentinel; jank/P95 from gfxinfo | `fps`, `fps.session` |
| Current | fuel gauge avg (`CURRENT_AVERAGE` / `bms/current_avg`) + rolling median of `current_now` (15) | calibrated sign, scale = `GLOBAL_SPF_CURRENT_NOW_UNIT` (sign ignored) | `battery.current.raw/median/avg`, `usage.current`, `charge.current` |
| Temperature (battery) | sticky `ACTION_BATTERY_CHANGED` (0.1 °C) → root shell fallback | range check, 3 s freshness cache | `battery.temperature`, `usage.temperature`, `charge.temperature` |
| Temperature (CPU/SoC) | `/sys/class/thermal/thermal_zone*/temp` (type-filtered) | milli/deci/plain decode, hottest zone | `temperature` (diagnostics) |
| RAM / swap | `/proc/meminfo` batch | used = total − available | `mem.used`, `mem.available`, `swap.used` |
| zRAM | `mm_stat` + `/proc/swaps` | uncompressed MB vs physical RAM MB + ratio (`mem_used_total` absent here) | `zram.uncompressed`, `zram.physical`, `zram.ratio` |
| CPU load | `/proc/stat` direct read, delta window | 100 − idle%, window logged | `cpu.load` |
| CPU freq | `scaling_cur_freq` per policy | kHz → MHz | `cpu.freq.policy0/6` |
| GPU load/freq | kgsl node (`gpu_busy_percentage`, `devfreq/cur_freq`) | %, Hz/kHz → MHz, clamped 0–100 | `gpu.load` |
| Charge speed | `ChargeCurve` → `ChargeSpeedStore` | per-plug session, integral io·dt | `charge.*` |
| Usage per app | `PowerUtilizationCurve` → `BatteryHistoryStore` | median current, dt, totalMs duration | `usage.*` |

## The parameter log (`MeasureLog`)

- Location: `/sdcard/Android/data/com.omarea.vtools/files/measure/measure-YYYYMMDD.csv`
  (rotates at 2 MB, keeps 7 files). Pull with
  `adb pull /sdcard/Android/data/com.omarea.vtools/files/measure`.
- Format: `time|parameter|value|unit|source|valid|extra`.
- Written off the main thread by a single-thread executor; global kill switch
  `MeasureLog.enabled`.
- Diagnostics ▸ "Measurement" section summarises live readings + log status.

## FPS record (sessions)

- Schema v2 (`FpsWatchStore`): `dt_ms`, `source`, `refresh_hz`, `jank_frames`,
  `frames`, `valid` — samples without a valid source are **not stored**.
- Sources: `measured_fps` is the kernel compositor counter, so a low value on
  a static screen is a *genuine* reading (kept); only 0/no-frames counts as
  invalid. gfxinfo is skipped for 60 s after 3 fruitless attempts (it costs a
  `dumpsys` round trip); `sf_counter` requires ≥ 2 fps.
- Stats (`util/fps/FpsMetrics`): dt-weighted average, min/max over valid
  samples (≥ 2 fps) after a 2 s grace window, smooth ratio normalised to the
  detected target refresh (90 % threshold), jank ratio, fever ratio.
- Sampling is fixed-delay 1 Hz (single-thread executor, no catch-up bursts);
  app switch / screen off ends the session (`time_end` written).
