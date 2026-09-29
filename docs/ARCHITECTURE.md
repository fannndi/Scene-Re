# Scene-Re Architecture

Device-exact Android tuning app (branch `vanilla`, single target: POCO X3 NFC /
surya / sm6150). Fully offline; root via APatch (Magisk-compatible env).

## Module map

```
app/src/main/java/com/omarea/
├── core/
│   ├── shell/                 # shell access (one door)
│   │   ├── ShellNodes.kt      #   single registry of every /sys, /proc, runtime path
│   │   ├── RootShell.kt       #   run(command) / read(node) / prop(name)
│   │   └── PropShell.kt       #   resetprop command builder (APatch path)
│   ├── profile/               # DOMAIN — pure Kotlin, JVM unit-tested
│   │   ├── ProfileKey.kt      #   canonical mode ids + fast<->custom alias
│   │   ├── TuningRepository.kt#   tuning.json IO (user copy > bundled asset)
│   │   ├── DeviceCaps.kt      #   available OPPs/governors + clamp math
│   │   ├── ProfilePlan.kt     #   ProfileOp + ProfilePlan data
│   │   ├── ProfilePlanner.kt  #   JSON -> ordered ops (no side effects)
│   │   ├── ProfileApplier.kt  #   ops -> shell + read-back verify (retry once)
│   │   ├── ParameterCatalog.kt#   Parameter.sh text generation (pure)
│   │   ├── ProfileSnapshot.kt #   live state -> user tuning JSON
│   │   ├── HwuiResolution.kt  #   pure hwui precedence rule
│   │   └── CpuSet.kt          #   cpu list parsing ("0-3,5")
│   └── control/               # EFFECTS — one owner per lifecycle
│       ├── ProfileController.kt # ON/OFF lifecycle, one-shot applies, boot state
│       ├── DaemonController.kt  # mi_thermald/miuibooster/scene_thermald
│       └── HwuiController.kt    # debug.hwui.renderer / ro.hwui.use_vulkan
├── scene_mode/
│   ├── ModeSwitcher.kt        # mode orchestration + source selection (thin)
│   ├── AppSwitchHandler.kt    # accessibility events -> controllers
│   └── CpuConfigInstaller.kt  # external /data/powercfg.sh detection only
├── store/                     # SharedPreferences keys + caches (SpfConfig, …)
├── vtools/                    # UI: fragments, activities, Compose screens
└── kr/                        # kr-script engine (frozen legacy; see below)
```

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
   ProfileApplier.apply(plan)         shell + verify + retry
   ProfileApplier.writeThermalProfileMax(...)   handoff to scene_thermald
   DaemonController.ensureOn()        stop MIUI daemons, run scene_thermald
   HwuiController.applyActive()       per-app > per-profile > default
```

## Invariants (do not break)

1. **One source of truth**: mode tuning comes from `tuning.json` (user copy at
   `/sdcard/Scene/profiles/<platform>.tuning.json` wins over the bundled
   `assets/powercfg/<platform>/tuning.json`). The legacy `CpuConfigStorage`
   mode files and script import/download flows are gone.
2. **`fast` is the legacy id of `custom`**. Never compare mode ids directly —
   use `ProfileKey.canonical()` / `ProfileKey.profile()`.
3. **Profile engine OFF means stock**: `ProfileController.release()` applies the
   bundled `release` profile, clears HWUI props and restores MIUI daemons.
   `ModeSwitcher` applies nothing while OFF.
4. **Daemons follow the engine state**: ON stops `mi_thermald`/`miuibooster`
   (init `stop`, no respawn) and runs `scene_thermald`; OFF reverses it.
5. **scene_thermald only lowers `scaling_max`**; the active profile's max is
   handed over via `/data/local/tmp/scene_thermald.profile_max`.
6. **HWUI has exactly one writer** (`HwuiController`):
   per-app override > active profile `hwui` value > system default;
   engine OFF resolves everything to default.
7. **Pure vs Android**: anything in `core/profile` must stay Android-free
   (org.json is fine) so `./gradlew :app:testDebugUnitTest` keeps working.
   Kernel thermal may hold `scaling_max_freq` below the plan; `VerifyPolicy`
   classifies that as expected (never fight hardware protection).
8. **Boot state**: the last mode is persisted in `GLOBAL_SPF_LAST_MODE`
   (props are volatile); `applyBootState()` re-applies init + mode + daemons.
9. Swap/zRAM code (`ActivitySwap`, `SwapUtils`, `assets/addin/*.sh` for swap)
   is intentionally untouched.

## Files on device

| Path | Meaning |
|---|---|
| `/sdcard/Scene/profiles/<platform>.tuning.json` | user tuning (edit via VS Code over adb) |
| `/sdcard/Scene/profiles/Parameter.sh` | auto-generated parameter catalog |
| `/sdcard/Scene/scene_thermald.log` | thermal daemon log |
| `/data/local/tmp/scene_thermald.sh` | deployed thermal daemon |
| `/data/local/tmp/scene_thermald.profile_max` | profile max handoff (`p0 p6`) |
| `/data/local/tmp/scene_thermald.state` | daemon thermal state |
| `/data/powercfg.sh` | external power-user script (overrides the engine) |

## How do I …

- **Add a tunable** → add it to `tuning.json` under `init` or a profile; if it is
  a new kernel node, map it in `ProfilePlanner` + `ShellNodes`; it shows up in
  `Parameter.sh` automatically. Add a planner test.
- **Add a device** → add `assets/powercfg/<platform>/tuning.json`; the launcher
  folder name must equal `PlatformUtils().getCPUName()`.
- **Change HWUI rules** → only `HwuiController` + `HwuiResolution` (+ test).
- **Debug on device** → `bash tools/scene-debug.sh [logcat_lines]` (adb + su,
  Markdown snapshot); in-app: Tools ▸ Diagnostics.

## Build & test

```
./gradlew assembleDebug              # fast compile check
./gradlew :app:testDebugUnitTest     # pure-logic tests, no device needed
./gradlew assembleRelease            # install with adb install -r
```

## kr-script system (frozen legacy)

`assets/kr-script/**` powers the Tools pages through the `kr/` engine
(`OpenPageHelper`, `ActionPage`). It is data-driven shell; do not extend it for
new features — new tunables belong to the JSON engine. Qualcomm pages and the
resurgence module installer are still functional and referenced by
`more.xml`/`other.xml`.

## Known follow-up extractions (not blocking)

- `FragmentHome.updateInfo()` collector — extract into `ui/home/HomeCollector`
  once a presenter interface is worth the churn.
- `ActivityCpuControl` live-read block — extract into `ui/cpucontrol/`.
