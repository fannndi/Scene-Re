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
10. SELinux direct writes are opt-in (default OFF) and must be verified after
    apply (`SepolicyOptimizer.apply()` returns a read/write status).

## Device facts (verified on target)

- CPU: policy0 = cpu0–5 Silver 300–1804800 kHz; policy6 = cpu6–7 Gold
  300–2304000 kHz. GPU Adreno 618, pwrlevels 0–6 (0 = max).
- `mi_thermald` re-locks `scaling_min_freq` when running; stopping it via init
  `stop` releases the lock.
- APatch's `resetprop` lives at `/data/adb/ap/bin/resetprop` (not on PATH).
- `/data/adb` execution is SELinux-blocked; deploy binaries to
  `/data/local/tmp/`.
