# AGENTS.md — Scene-Re (lean)

Android performance-tuning app for rooted devices. Modified fork of
[Scene5 OpenSource / helloklf/vtools](https://github.com/helloklf/vtools)
(GPL-3.0), rebranded to **Scene-Re**. Personal build, fully offline,
**single target device: POCO X3 NFC / surya / sm6150** (Android 12 / MIUI,
APatch root).

## Quick orientation

| Layer | Path | Notes |
|---|---|---|
| App module | `app/` (`com.omarea.vtools`) | UI (Compose + Miuix + XML/databinding), features |
| Common module | `common/` (`com.omarea.common`) | `KeepShell` (persistent root shell), shared UI |
| Script engine | `krscript/` (`com.omarea.krscript`) | kr-script engine (offline/local pages only) |
| Tuning profiles | `app/src/main/assets/powercfg/` | **sm6150**: `powercfg.sh` dispatcher + `profiles/<mode>.json` (powersave/balance/performance/fast/pedestal/screen_off) + `powercfg-base.sh`/`powercfg-utils.sh` + `profile.json` for `scene-scheduler` |
| kr-script pages | `app/src/main/assets/kr-script/` | aosp, display, apps, developer, common |
| Addin scripts | `app/src/main/assets/addin/` | one-shot shell actions |

## Kept features (after the lean cut)

home monitor (CPU/RAM/battery/temps + floating monitors), Device Profile
(CPU/GPU freq, msm_thermal tunables — row in the **Adjust** tab, above
Apps Profile; **Power profiles** card edits each mode's
`profiles/<mode>.json` and saves it on-device), **Profile Service**
(master switch, see below) with powercfg modes
(+scene-scheduler) and scene-mode per-app options (power mode, brightness,
GPS, rotation, monitor, **cgroup memory**), app scene list (Apps Profile:
tap an app for the per-app tuning screen, long-press for the power mode
dialog),
float power selector (per-app brightness/GPS/cgroup tap-to-cycle + refresh
rate), charge info + controller, power-utilization stats, FPS chart +
overlay, floating-monitor entry in the Features tab (top bar keeps only
Settings), kr-script pages (AOSP/display/apps/developer), boot worker,
accessibility service (app-switch handling) with a status banner on the
Adjust tab that distinguishes *enabled in Settings* from *actually
bound* and offers a shell-based rebind, misc settings/theme, battery
monitor service.

## Profile Service (master switch)

The Adjust tab keeps three cards: **Profile Service** (master switch),
Device Profile, Apps Profile. `Profile Service` replaces the old
"Dynamic response" card; its four sub-options (global default, standby,
strict mode, delayed switching) are gone.

- ON: the powercfg mode is applied per foreground app (Apps Profile's
  per-app modes ride on this switch; the fallback mode is **Balance**),
  Device Profile profiles are live, and `ProfileServiceGuard` disables
  the services that would override the tuning (Tier 3): MIUI booster
  (`persist.sys.enable_miui_booster=0` + `ctl.stop miuibooster`), QTI
  perf HAL (`vendor.perfservice`, `perf-hal-2-2`), `mi_thermald`, plus
  Game Booster settings off and `com.qualcomm.qti.performancemode`
  disabled. A 30 s guard re-applies the active mode when its caps get
  overwritten (skipped while locked/screen-off).
- Soft thermal safety: since `mi_thermald` is stopped, the guard starts
  it again at ≥72 °C and stops it below 60 °C. The kernel's own
  `step_wise` trips (110/120 °C CPU, 95 °C GPU) remain the hard backstop.
- OFF: `ProfileServiceGuard` restores every service and applies the
  `stock` action (ROM/kernel defaults), so the system runs stock.
- Guard commands run on the secondary keep-shell; the primary is used by
  the splash activity on the main thread (sharing it caused an ANR).

## Power profiles (powercfg)

- `powercfg.sh` is a dispatcher: `init` runs `powercfg-base.sh`, `<mode>`
  loads `profiles/<mode>.json` and applies it, `stock` restores
  ROM/kernel defaults (full ranges, kernel schedutil/core_ctl defaults,
  cfq, bw minimum), `screen_off`/`screen_on`
  are driven by `PowerCfgScreenHook` (screen-off lite profile; screen-on
  re-applies the mode from the `vtools.powercfg` prop).
- Runtime layout (app-private): `files/powercfg.sh`, `files/profiles/default/*.json`
  (refreshed on install) and `files/profiles/*.json` (user edits, never
  overwritten). The dispatcher prefers the user file and falls back to
  `default/`. Device Profile → Power profiles edits the user file.
- Values are derived from the freqbench SM7150-AC (= SD732G) energy table:
  little A55 sweet spot 1.61–1.71 GHz, big A76 1.21–1.32 GHz, big
  efficiency collapses above 1.84 GHz. Energy-per-task favours
  race-to-idle, so `hispeed_freq` parks at the sweet spot and only `fast`
  goes above 1.94 GHz. Real caps: big 2304000, little 1804800.
- Knobs: cpufreq min/max + schedutil (hispeed/load/rate limits), input
  boost, sched_boost/stune, migrate thresholds, core_ctl, cpuset, GPU
  governor + max/min power level, gpubw floor, block scheduler /
  read_ahead / nr_requests / iostats, devfreq bw policy.
- **No UFS knobs** (deliberate): writing
  `/sys/class/devfreq/1d84000.ufshc/min_freq` can block forever on this
  MIUI kernel, which used to stall the tuning script. Focus is SoC + GPU.

### ROM services that interfere (measured on this device)

- `mi_thermald` throttles the **big cluster only**, writing
  `scaling_max_freq` directly under sustained load (~60–70 °C):
  2304000 → 1939200 → 1555200 → 1209600. `thermal_message/cpu_limits`
  stays empty; `thermal_message/sconfig` selects the thermal profile
  (0 = normal). Leave sconfig at 0 unless deliberately testing.
- `miuibooster` (`MiuiBoosterService`) acquires QTI **perf locks**
  (`perf_lock_acq`/`perf_lock_rel` via `vendor.qti.hardware.perf@2.2-service`)
  and reads/boosts the `*-lat` devfreq nodes; Game Booster was off here
  (`gb_boosting=0`). `set_cpu_freq` resets
  `/sys/module/msm_performance/parameters/cpu_max_freq` on every apply so
  stale locks do not cap us.
- MIUI `perfservice` rejects `cpuset/background` and `cpuset/foreground`
  writes (`top-app`/`system-background` are accepted) — cpuset writes are
  best-effort and silent.
- `com.miui.powerkeeper` / `com.qualcomm.qti.performancemode` can also
  apply power/perf profiles; nothing was observed overriding the
  profiles during the load tests.

## Removed features (do not reintroduce)

Dynamic response sub-options (**global default mode**, **standby mode**,
**strict mode**, **delayed switching** — the Profile Service master
switch replaces them; per-app switching is always strict and screen-off
is handled by `profiles/screen_off.json`),
triggers/timing-tasks/custom-commands, standby mode, freeze apps, processes
manager + float task manager, swap/zRAM manager, dynamic memory boost,
auto-click install, skip-ad, notification filter, immersive mode, thermal
disguise, native MIUI thermal editor + **Thermal & FPS-Lock kr page
(Scene-Online)**, MIUI online update check switch, img/TWRP page,
developer OTA page, self-rescue + thermal-remove pages, UFS/eMMC
storage-life pages, **block keys** (per-app key interception), reboot/power
menu, **Xiaomi, Battery&Charge and Others kr pages**, AOSP night mode /
rotation / status icons / network checker / NTP, UI/Display brightness /
color calibration / refresh rate / split view / navbar, Applications
camera HAL / launcher / live wallpaper / default apps, Developer logcat /
ADB / error dialogs / sandbox / notch, sundry addin
(DPI, model spoof, WiFi/MAC), Img partition flashing, applications
manager/hidden apps/app details, Magisk props editor, Magisk module
browser, QS tile + static shortcuts, floating debug log, **Xposed module +
vaddin plugin**, CompileService (dex2oat), kr-script online page engine
(`ActionPageOnline`, webview downloader), all cloud services
(update checker, Scene-Online, auto-skip configs), **config-source
switcher** (Classic/Performance preset picker, local `.sh` import, online
config download — the built-in profile still auto-installs at startup),
**blurred dialog backgrounds** (dialogs use a plain translucent scrim +
dim; `FastBlurUtility`/`BlurBackground` are gone).

## Identity / rebrand facts

- **applicationId**: `com.fannndi.scenere`; Java/Kotlin packages stay `com.omarea.*`.
- **Shell system properties** `vtools.*` are kept on purpose (runtime contract
  with the remaining shell scripts).
- Runtime references to the app package live in `assets/addin/*.sh`,
  `library/shell/AccessibilityServiceUtils` and `powercfg` scripts; verify with
  `grep -rn "com.omarea.vtools" app/src/main/assets` before releases
  (only the old namespace should remain, never the old package string).

## Build

```
./gradlew :app:assembleDebug        # debug APK (default artifact)
./gradlew :app:assembleRelease      # signed release (not built by default)
./gradlew test                      # unit tests (junit; real tests exist:
                                     # app/src/test, e.g. dumpsys parsing)
```

- Toolchain: Gradle 9.8.0, AGP 9.4.1, Kotlin 2.4.20, Compose BOM 2026.09.00,
  Miuix KMP 0.8.8, coroutines 1.11.0, work 2.12.0, appcompat 1.8.0,
  material 1.14.0, constraintlayout 2.2.2.
- SDK: `/home/fannndi/Android/Sdk` (compileSdk 37, minSdk 29,
  build-tools 36.0.0, NDK 21.0.6113669, CMake 3.22.1); ABI: **arm64-v8a only**.
  `local.properties` is required.
- Signing: `keystore.properties` (gitignored). JKS: `scenere.jks`, alias
  `scenere`; debug builds use the same key as release.
- `versionCode` = git commit count (needs ≥1 commit).
- Device: POCO X3 NFC (surya); `adb` at `~/Android/Sdk/platform-tools/adb`.

## Hard rules

1. Offline by design; single-device (sm6150) target — do not add other SoC
   profiles, cloud/Scene-Online features or ROM-specific kr-script pages back.
2. The lean cut is deliberate; restoring a removed feature needs an explicit
   request. Git history has everything (pre-cut baseline `87a3d89`).
3. After changes touching build.gradle/assets/manifest: run
   `./gradlew :app:assembleDebug` and `./gradlew test` before committing.
4. Keep commits small and scoped; feature cuts are labeled `cut(...)`.
5. GPL-3.0: keep `LICENSE`, `NOTICE` and upstream attribution.
