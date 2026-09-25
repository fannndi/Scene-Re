# Scene

Android performance & gaming toolkit for **rooted Xiaomi phones with Qualcomm (Snapdragon) SoCs**.

> **Requirements:** rooted device — any root manager (Magisk / KernelSU / APatch) or plain `su` — plus busybox.  
> **Scope:** Xiaomi / Redmi / POCO on Qualcomm platforms only. Exynos, MediaTek, and other brands are **not supported**.  
> **No Xposed / Zygisk / LSPosed** — root-only build.

Some advanced features can affect system boot. Read every prompt before using them, and do not use this app on a device with important data.

---

## Features

- **Scene modes** — powersave / balance / performance / fast profiles with per-app overrides
- **Profile options** — frequency limiter (CPU + GPU), lite mode, governor / I/O scheduler preference, thermal guard, Snapdragon bus-DRAM & GPU boost, extra kernel tweaks (all reversible)
- **Battery aware** — follow the system battery saver, bypass charging with per-caller reasons, charge protection level, battery health report, thermal PID, boot guard
- **CPU & GPU control** — cluster frequencies, governors, Adreno power levels, core online, cpuset
- **Dynamic response** — accessibility-driven runtime tuning, optional app_process fallback monitor
- **Game toolkit** — bundled game list (Encore Tweaks baseline), preload, DND, renderer / resolution / FPS per game, session report (battery drain / temperature / FPS history)
- **App freezer** — suspend/disable apps with auto-freeze timing (system suspend mode; no Xposed launcher hooks)
- **Qualcomm tools** — DDR / LLCC bandwidth, msm_booster / msm_perfd scripts
- **Monitors** — FPS chart, battery stats, float monitor, kernel feature report
- **Misc add-ins** — dex2oat optimization, diagnostics bundle, config backup

## Supported platform (bundled powercfg)

This build targets a single device: **POCO X3 NFC / POCO X3 (`surya` / `karna`) — Snapdragon 732G (SM7150-AC)**. Android reports its platform as `sm6150`, so the bundled profile lives in `powercfg/sm6150/` and other SoC profiles were removed. The CPU/GPU OPP tables and kernel node notes for this device are documented in `app/src/main/assets/powercfg/sm6150/powercfg-utils.sh`.

## Project structure

| Path | Description |
|---|---|
| `app/` | Main Android application (Kotlin/Java + assets) |
| `common/` | Shared shell/root utilities |
| `krscript/` | Script engine module (kr-script pages) |
| `others/` | Dev scratch space (test scripts, fps-chart assets) |

Key assets under `app/src/main/assets/`:

- `powercfg/sm6150/` — the device performance profile (active / conservative / base / utils)
- `kr-script/` — feature script pages (`aosp/`, `qualcomm/`, `display/`, `battery/`, `apps/`, `developer/`, `other/`)
- `addin/`, `toolkit/` — root helper scripts and busybox

## Build

```powershell
# requires JDK 17, Android SDK, NDK 25.2.9519653, CMake 3.22.1
copy keystore.properties.example keystore.properties   # then fill real values for release
./gradlew assembleDebug
```

- `keystore.properties` is required at configuration time (git-ignored); debug builds do not use the signing config.
- Output APK: `app/build/outputs/apk/**/Scene_5.0_OpenSource_r*.apk`

## Risk disclosure

Scene requires root and can modify CPU/GPU/frequency/thermal parameters. Misuse can cause data loss or boot failure. Use at your own risk; keep backups. The authors provide no guarantee of recovery support.

## License

See [LICENSE](LICENSE).
