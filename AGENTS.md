# AGENTS.md — Scene-Re (branch `vanilla`)

Single-device tuning app: **POCO X3 NFC / surya / sm6150**, MIUI 12, root via
**APatch**. The app is fully offline. Read `docs/ARCHITECTURE.md` and
`docs/PROFILE-ENGINE.md` before touching tuning code.

## Quick orientation

| Layer | Path | Rule |
|---|---|---|
| Tuning core (engine) | `app/src/main/java/com/omarea/engine/` | one file, one responsibility; JVM-test pure parts |
| Android lifecycle | `runtime/` (ModeSwitcher, SceneMode, BootWorker, services) | mode orchestration vs external `/data/powercfg.sh` |
| UI | `ui/` (fragments, activities, Compose) | no tuning logic; call controllers |
| Prefs/caches | `data/` | `SpfConfig` owns the keys |
| Measurement | `util/measure` (SysReader, MeasureLog, MemSnapshot, ThermalMath), `util/fps`, `util/battery` | one consistent sample per tick; every parameter logged (`docs/MEASUREMENT.md`) |
| Benchmark | `benchmark/` (runner, sampler, metrics, report, workload), `ui/activity/ActivityBenchmark` | profile-vs-stock proof; bundles under `files/benchmark/` (`docs/BENCHMARK.md`) |
| Shared shell/UI kit | `common/` | `KeepShell` lives here; engine uses `RootShell` instead |

There is **no kr-script** and **no `:krscript`/`:common` module** anymore —
single `:app` module. App-logic `.sh` files were ported to Kotlin; the only
shell assets left are swap/zRAM (hard rule 6), the rescue payload, and
`scene_thermald.sh` (fallback for `ThermalService`, delete after device
verification).

## Commands

```
./gradlew assembleDebug            # compile check
./gradlew :app:testDebugUnitTest   # pure-logic tests (no device)
./gradlew assembleRelease          # build APK
bash tools/scene-debug.sh [lines]  # device snapshot (adb + su, Markdown)
bash tools/ui-map.sh [screen]      # regenerate UI screenshots + element JSON
bash tools/agent-tap.sh <screen> <text|id:part>   # tap by element, not coords
```

See `docs/AGENT-RND.md` for the full agent workflow (diagnostics export,
shell log, UI-MAP usage).

## Hard rules

1. Never compare mode ids directly — `fast` == `custom` (`ProfileKey`).
2. HWUI props have exactly one writer: `HwuiController`.
3. Engine OFF ⇒ stock: nothing may apply tuning in that state.
4. Daemons follow engine state (`DaemonController`).
5. The thermal guard only lowers `scaling_max` (`ThermalController`/`ThermalService`);
   never add min-freq locks there.
6. Don't touch swap/zRAM features or their shell assets.
7. New tunables go to `tuning.json` + `ProfilePlanner` (+ test).
8. Prefer small files with a `Responsibility / Non-goals` KDoc header.
9. Root access in `engine/` goes through `RootShell` only — no direct
   `KeepShellPublic` calls in engine files.
10. SELinux rules ship via the auto-provisioned APatch module
    (`sepolicy.rule`, applied at boot); never call `magiskpolicy --apply
    --live` at runtime — it is ineffective on this APatch build and strips
    APatch's own boot-time patches. Direct writes are opt-in (default OFF),
    verified after apply, and fall back to the root shell per op.
11. New kernel node families go into `KernelCompat` (probes + hint) — locked
    features must be reported, never silently skipped
    (`docs/COMPATIBILITY.md`).
12. **Charging is read-only** — never write `/sys/class/power_supply/*`
    charge parameters (limits, PD, step, charge_full, capacity, enable/
    suspend). The only writer is the guarded, one-time
    `runtime/ChargeStockRestorer` that undoes legacy artifacts at boot.
13. Profile editing is **config-only** (`ProfileStore`/`ProfileDoc`); no path
    may write a running profile's parameters — the editor is locked while the
    engine is ON and read-only under TRUE OFF.

14. **TRUE OFF wins over everything** — when `TrueOff.isOff` is true no
    parameter may be written by any path (guards sit at each writer;
    `force` is only for TrueOff's own enter/exit). Adding a new writer?
    Guard it with `TrueOff.allowsWrite(context)` and route manual UI
    actions through `TrueOff.guardOrToast(activity)`.

15. Boot applies wait for the ROM's `qcom-post-boot` (`RomBootGate`) and
    engine OFF restores the per-boot `StockSnapshot`; the `release` profile
    must mirror the ROM post_boot block (`TuningJsonTest`).

## Device facts (verified on target)

- CPU: policy0 = cpu0–5 Silver 300–1804800 kHz; policy6 = cpu6–7 Gold
  300–2304000 kHz. GPU Adreno 618, pwrlevels 0–6 (0 = max).
- SoC is **SM7150 "moorea"** (soc_id 365/366) even though
  `ro.board.platform=sm6150`; perf HAL target name is **`sdmmagpie`**.
  Stock ROM audit: `docs/STOCK-ROM.md`.
- `mi_thermald` re-locks `scaling_min_freq` when running; stopping it via init
  `stop` releases the lock. `sconfig` picks its thermal config
  (`docs/reference/mi-thermal/`).
- `thermal_message/temp_state` is world-writable (0666); `sconfig` is 0664.
- MIUI cpusets `game`/`gamelite`/`vr`/`background/untrustedapp` exist.
- There is **no `perfd`** on this ROM; the perf daemon is
  `vendor.qti.hardware.perf@2.2-service` (+ `/system_ext/bin/perfservice`).
- Kernel reference (node semantics, thermal, drift): `docs/KERNEL.md`.
- APatch's `resetprop` lives at `/data/adb/ap/bin/resetprop` (not on PATH).
- `/data/adb` execution is SELinux-blocked; deploy binaries to
  `/data/local/tmp/`.
