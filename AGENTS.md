# AGENTS.md — Scene-Re (branch `vanilla`)

Single-device tuning app: **POCO X3 NFC / surya / sm6150**, MIUI 12, root via
**APatch**. The app is fully offline. Read `docs/ARCHITECTURE.md` and
`docs/PROFILE-ENGINE.md` before touching tuning code.

## Quick orientation

| Layer | Path | Rule |
|---|---|---|
| Domain (pure Kotlin, tested) | `app/src/main/java/com/omarea/core/profile/` | keep Android-free; add tests for changes |
| Effects (shell/props/daemons) | `core/control/`, `core/shell/` | one owner per lifecycle |
| Mode orchestration | `scene_mode/ModeSwitcher.kt` | engine vs external `/data/powercfg.sh` |
| UI | `vtools/` (fragments, activities, Compose) | no tuning logic; call controllers |
| Legacy shell pages | `assets/kr-script/` + `kr/` | frozen; don't extend |

## Commands

```
./gradlew assembleDebug            # compile check
./gradlew :app:testDebugUnitTest   # pure-logic tests (no device)
./gradlew assembleRelease          # build APK
bash tools/scene-debug.sh [lines]  # device snapshot (adb + su, Markdown)
```

## Hard rules

1. Never compare mode ids directly — `fast` == `custom` (`ProfileKey`).
2. HWUI props have exactly one writer: `HwuiController`.
3. Engine OFF ⇒ stock: nothing may apply tuning in that state.
4. Daemons follow engine state (`DaemonController`).
5. `scene_thermald` only lowers `scaling_max`; never add min-freq locks there.
6. Don't touch swap/zRAM features or their shell assets.
7. New tunables go to `tuning.json` + `ProfilePlanner` (+ test), never to
   kr-script.
8. Prefer small files with a `Responsibility / Non-goals` KDoc header.

## Device facts (verified on target)

- CPU: policy0 = cpu0–5 Silver 300–1804800 kHz; policy6 = cpu6–7 Gold
  300–2304000 kHz. GPU Adreno 618, pwrlevels 0–6 (0 = max).
- `mi_thermald` re-locks `scaling_min_freq` when running; stopping it via init
  `stop` releases the lock.
- APatch's `resetprop` lives at `/data/adb/ap/bin/resetprop` (not on PATH).
- `/data/adb` execution is SELinux-blocked; deploy binaries to
  `/data/local/tmp/`.
