# Scene-Re Architecture

Device-exact Android tuning app (branch `vanilla`, single target: POCO X3 NFC /
surya / sm6150). Fully offline; root via APatch (Magisk-compatible env).

## Module map

```
app/src/main/java/com/omarea/
├── engine/                    # tuning core — one file, one responsibility
│   ├── RootShell.kt           #   THE single door to the root shell
│   ├── ShellNodes.kt          #   registry of every /sys, /proc, runtime path
│   ├── KernelCompat.kt        #   kernel/ROM capability registry + lock table
│   ├── PropShell.kt           #   resetprop command builder (APatch path)
│   ├── TuningRepository.kt    #   tuning.json IO (user copy > bundled asset)
│   ├── ProfileKey.kt          #   canonical mode ids + fast<->custom alias
│   ├── DeviceCaps.kt          #   available OPPs/governors + clamp math
│   ├── ProfilePlan.kt         #   ProfileOp + ProfilePlan data
│   ├── ProfilePlanner.kt      #   JSON -> ordered ops (pure, JVM-tested)
│   ├── ProfileApplier.kt      #   ops -> direct write | shell + verify (retry once)
│   ├── ProfileController.kt   #   ON/OFF lifecycle, one-shot applies, boot state
│   ├── DaemonController.kt    #   mi_thermald/miuibooster + thermal guard owner
│   ├── ThermalController.kt   #   pure thermal state machine (JVM-tested)
│   ├── ThermalService.kt      #   always-on loop: clamp scaling_max when hot
│   ├── HwuiController.kt      #   debug.hwui.renderer / ro.hwui.use_vulkan
│   ├── SepolicyOptimizer.kt   #   scoped magiskpolicy rules + node chmod
│   ├── DirectWrite.kt         #   opt-in rootless sysfs writes
│   ├── TweakCommands.kt       #   Tweaks-screen command builders + parsers
│   ├── FastCharge.kt          #   fast-charge limit commands (ported addin/*.sh)
│   ├── BusDcvs.kt             #   Qualcomm bus DCVS domains
│   ├── ModuleHooks.kt         #   systemless file hooks
│   ├── ChargeController.kt    #   charge pause/resume
│   ├── DiagnosticsCollector.kt#   read-only Markdown/JSON snapshot
│   └── … pure logic (ParameterCatalog, CpuSet, HwuiResolution, VerifyPolicy,
│       ProfileSnapshot, SocInfo) — JVM unit-tested
├── runtime/                   # Android lifecycle: services, receivers, mode switching
│   ├── ModeSwitcher.kt        #   mode orchestration + source selection (thin)
│   ├── SceneMode.kt           #   accessibility scene rules + freeze executor
│   ├── AppSwitchHandler.kt    #   accessibility events -> controllers
│   ├── BootWorker.kt          #   boot re-apply (SELinux, profile, freeze)
│   ├── CpuConfigInstaller.kt  #   external /data/powercfg.sh detection only
│   └── … receivers, tile, compile/notification services
├── data/                      # SharedPreferences keys + caches (SpfConfig, …)
├── ui/                        # fragments, activities, Compose screens, dialogs
├── util/                      # battery/process/root helpers used by UI+runtime
└── common/                    # shared shell (KeepShell) + UI toolkit
```

Single Gradle module `:app` (the old `:krscript` and `:common` modules are gone).

## Data flow

```
mode tap / app switch / boot
        │
ModeSwitcher.executePowercfgMode(mode, app)
        │  profile engine OFF?  -> remember mode only (device stays stock)
        │  external script?     -> sh /data/powercfg.sh <mode>
        ▼
ProfileController.applyMode(mode)
   TuningRepository.read()            user copy > bundled asset
   ProfilePlanner.planProfile(...)    pure: clamp OPPs, validate governors
   DaemonController.ensureOn()        stop MIUI daemons, start ThermalService
   ProfileApplier.apply(plan)         direct write? -> shell + verify + retry
   ProfileApplier.writeThermalProfileMax(...)   handoff to the thermal guard
   HwuiController.applyActive()       per-app > per-profile > default
```

## Invariants (do not break)

1. **One source of truth**: mode tuning comes from `tuning.json` (user copy at
   `/sdcard/Scene/profiles/<platform>.tuning.json` wins over the bundled
   `assets/powercfg/<platform>/tuning.json`).
2. **`fast` is the legacy id of `custom`**. Never compare mode ids directly —
   use `ProfileKey.canonical()` / `ProfileKey.profile()`.
3. **Profile engine OFF means stock**: `ProfileController.release()` applies the
   bundled `release` profile, clears HWUI props and restores MIUI daemons.
   `ModeSwitcher` applies nothing while OFF.
4. **Daemons follow the engine state**: ON stops `mi_thermald`/`miuibooster`
   (init `stop`, no respawn) and runs `ThermalService`; OFF reverses it.
   The bundled `assets/scene_thermald.sh` is only a start-failure fallback.
5. **The thermal guard only lowers `scaling_max`** (never min-freq, cores or
   governor); the active profile's max is handed over via
   `/data/local/tmp/scene_thermald.profile_max`. Kernel trips stay the safety
   net. Policy lives in pure `ThermalController`; the loop in `ThermalService`.
6. **HWUI has exactly one writer** (`HwuiController`):
   per-app override > active profile `hwui` value > system default;
   engine OFF resolves everything to default.
7. **Pure vs Android**: everything marked JVM-tested in the module map must
   stay Android-free (org.json is fine) so
   `./gradlew :app:testDebugUnitTest` keeps working. Kernel thermal may hold
   `scaling_max_freq` below the plan; `VerifyPolicy` classifies that as
   expected (never fight hardware protection).
8. **Boot state**: the last mode is persisted in `GLOBAL_SPF_LAST_MODE`
   (props are volatile); `applyBootState()` re-applies SELinux rules + init +
   mode + daemons.
9. **Swap/zRAM code** (`ActivitySwap`, `SwapUtils`, `assets/addin/swap_control.sh`,
   `zram_control.sh`, `force_compact.sh`) is intentionally untouched.
10. **Root access has one door**: `engine/RootShell` (the engine never talks to
    `KeepShellPublic` directly). SELinux rules are delivered through the
    auto-provisioned APatch module (`SepolicyModule`, sepolicy.rule applied at
    post-fs-data); the runtime `magiskpolicy --live` path is a no-op on this
    APatch build and is deliberately not used. Direct sysfs writes are opt-in
    (`GLOBAL_SPF_DIRECT_WRITES`, default OFF), verified after apply, and fall
    back to the root shell per op.
11. **No silent no-ops**: a tuning key whose kernel node is missing is
    **locked** — `ProfileApplier` reports `SCENE_MISSING`, the catalog marks
    it, and `KernelCompat` (registry + probe) feeds the Tweaks/diagnostics
    lock list. New node families must be registered there
    (`docs/COMPATIBILITY.md`, port wishlist in `docs/KERNEL.md`).
12. **The app ships no kr-script** and no app-logic `.sh` beyond: the rescue
    module payload (external module = by definition shell), the swap/zRAM
    assets (rule 9), and `scene_thermald.sh` (fallback only, pending device
    verification of `ThermalService`).

## Files on device

| Path | Meaning |
|---|---|
| `/sdcard/Scene/profiles/<platform>.tuning.json` | user tuning (edit via VS Code over adb) |
| `/sdcard/Scene/profiles/Parameter.sh` | auto-generated parameter catalog |
| `/sdcard/Scene/scene_thermald.log` | thermal log (legacy shell daemon) |
| `/data/local/tmp/scene_thermald.profile_max` | profile max handoff (`p0 p6`) |
| `/data/local/tmp/scene_thermald.state` | thermal state (mirror for diagnostics) |
| `/data/local/tmp/scene_policy.rules` | last generated SELinux rule set |
| `/data/adb/modules/scene_sepolicy/sepolicy.rule` | **effective** SELinux rules (APatch applies at boot) |
| `/data/powercfg.sh` | external power-user script (overrides the engine) |
| `/data/vendor/thermal/decrypt.txt` | mi_thermald's active config (decrypted) |
| `/data/vendor/thermal/thermal.dump` | mi_thermald runtime log (clamp events) |

## Device facts

- SoC **SM7150 "moorea"** (soc_id 365/366), `ro.board.platform=sm6150`,
  perf target `sdmmagpie`; full stock audit in `docs/STOCK-ROM.md`.
- Kernel side reference (base, node semantics, drift): `docs/KERNEL.md`.
- Thermal config map (sconfig → `thermal-<x>.conf`) and all decrypted
  configs: `docs/reference/mi-thermal/` (AES-128-CBC, key/IV
  `thermalopenssl.h`). Only sconfig values 0/8/9/10/12/13/15/16 have
  shipped configs.
- `thermal_message/sconfig` 0664, `temp_state` 0666; MIUI cpusets
  `game`/`gamelite`/`vr` exist.
- No `perfd` binary: perf daemon = `vendor.qti.hardware.perf@2.2-service`
  + `/system_ext/bin/perfservice`.

## How do I …

- **Add a tunable** → add it to `tuning.json` under `init` or a profile; if it is
  a new kernel node, map it in `ProfilePlanner` + `ShellNodes`; it shows up in
  `Parameter.sh` automatically. Add a planner test.
- **Add a device** → add `assets/powercfg/<platform>/tuning.json`; the launcher
  folder name must equal `PlatformUtils().getCPUName()`.
- **Change HWUI rules** → only `HwuiController` + `HwuiResolution` (+ test).
- **Change thermal behaviour** → thresholds in `ThermalController` (+ test);
  node I/O in `ThermalService`; never add min-freq locks.
- **Debug on device** → `bash tools/scene-debug.sh [logcat_lines]` (adb + su,
  Markdown snapshot); in-app: Tools ▸ Diagnostics (includes an SELinux avc
  meter and direct-write status).

## Build & test

```
./gradlew assembleDebug              # fast compile check
./gradlew :app:testDebugUnitTest     # pure-logic tests, no device needed
./gradlew assembleRelease            # install with adb install -r
```

## Known follow-up extractions (not blocking)

- `FragmentHome.updateInfo()` collector — extract into `ui/home/HomeCollector`
  once a presenter interface is worth the churn.
- `ActivityCpuControl` live-read block — extract into `ui/cpucontrol/`.
- Delete `assets/scene_thermald.sh` + `assets/addin/{fast_charge,freeze_executor,install_busybox}.sh`
  after `ThermalService` and the Kotlin ports are verified on the device
  (assets are currently unreferenced but kept as a rollback aid).
