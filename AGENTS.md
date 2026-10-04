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
| Tuning profiles | `app/src/main/assets/powercfg/` | **sm6150** + `scene-scheduler` binary only |
| kr-script pages | `app/src/main/assets/kr-script/` | aosp, display, apps, developer, common |
| Addin scripts | `app/src/main/assets/addin/` | one-shot shell actions |

## Kept features (after the lean cut)

home monitor (CPU/RAM/battery/temps + floating monitors), Device Profile
(CPU/GPU freq, msm_thermal tunables — row in the **Adjust** tab, above
Apps Profile), powercfg modes (+scene-scheduler), dynamic response /
scene-mode per-app options (power mode, brightness, GPS,
rotation, monitor, **cgroup memory**), app scene list (Apps Profile: tap an
app for the per-app tuning screen, long-press for the power mode dialog),
float power selector (per-app brightness/GPS/cgroup tap-to-cycle + refresh
rate), charge info + controller, power-utilization stats, FPS chart +
overlay, floating-monitor entry in the Features tab (top bar keeps only
Settings), kr-script pages (AOSP/display/apps/developer), boot worker,
accessibility service (app-switch handling), misc settings/theme, battery
monitor service.

## Removed features (do not reintroduce)

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
config download — the built-in profile still auto-installs at startup).

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
./gradlew test                      # unit tests (junit; test deps active)
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
