# Scene-Re

Scene-Re is an open-source **root/system tuning tool for Qualcomm Snapdragon Android devices**.
It is a fork of [Scene / vtools](https://github.com/omarea/vtools) (also known as 微工具箱) maintained by
[fannndi](https://github.com/fannndi), focused on a clean, Qualcomm-only, English/Indonesian codebase.

> **Warning:** Scene-Re requires a rooted phone and busybox. Some advanced functions can affect system
> stability or prevent the device from booting. Use it at your own risk and keep a backup.

## Features

- **CPU control** - per-cluster frequency limits, governors, core online/offline, cpuset tuning.
- **GPU (Adreno/kgsl)** - frequency limits, governor, min/max/default power level.
- **Scheduling profiles (powercfg)** - bundled profiles for many Snapdragon platforms plus custom
  `/data/powercfg.sh` sources, dynamic per-app response, strict mode.
- **App freeze** - suspend/disable background apps, unfreeze on launch, pinned shortcuts, Xposed add-on support.
- **Thermal** - Qualcomm `msm_thermal` controls and MIUI thermal-config editor.
- **Monitoring** - live RAM/CPU/GPU/FPS/battery overview, FPS recording sessions, floating monitors.
- **System extras** - MIUI/ColorOS/Flyme/AOSP tweaks through the kr-script page engine, timing tasks,
  triggers, charge control, process manager, Magisk helpers.
- **Add-ons** - `kr-script` pages and the optional Scene Xposed module (`com.omarea.vaddin`).
- **Three privilege modes** - run Scene with **Root**, **Shizuku** (shell-level access, no root) or
  **Non-root** (monitoring only). The mode is selected in Features -> Privilege mode and is applied to
  every shell command.

## Requirements

- **Qualcomm Snapdragon** device (`ro.board.platform`). Non-Qualcomm SoCs are not supported.
- One of the following privilege modes:
  - **Root** (Magisk, KernelSU, SuperSU, etc.) with a working `su` shell - full feature set.
  - **Shizuku** - install [Shizuku](https://shizuku.rikka.app/), start its service (wireless debugging
    or root) and grant Scene the API permission; sysfs writes stay unavailable.
  - **Non-root** - no setup; read-only monitoring.
- **Busybox** (the app can install a bundled one) for root/Shizuku modes.
- Android 7.0+ (minSdk 24).

## Languages

English is the default language; Indonesian is available in system settings (`values-in`).
Only these two languages are maintained.

## Build

Prerequisites:

- JDK 17-25 (Android Studio JBR works). Gradle 9.1 supports Java up to 25.
- Android SDK with `compileSdk 36`, build-tools 36, NDK `28.2.13676358`.
- `local.properties` with `sdk.dir=...` (git-ignored).
- `keystore.properties` is optional; without it release builds are unsigned and debug builds still work.

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew :app:assembleDebug     # debug APK
.\gradlew :app:assembleRelease   # R8-minified release APK (unsigned without keystore.properties)
.\gradlew test                   # unit tests
```

APKs are written to `app/build/outputs/apk/<variant>/Scene_5.0_OpenSource_r<versionCode>_<variant>.apk`,
where `versionCode` is the current git commit count.

## Security / compatibility notes

Recent security hardening changed a few externally visible behaviors. If you drive Scene from automation
apps (Tasker, MacroDroid, etc.), read this:

- `SceneTaskIntentService` is no longer exported. External apps can no longer start timing tasks by sending
  the `com.omarea.scene_mode.TimingTaskReceiver` action. Timing tasks still run normally from alarms and
  from inside the app.
- `ActionPageOnline` is no longer exported. External apps can no longer open arbitrary web pages with the
  kr-script root bridge attached. Pages opened from Scene itself (including shortcuts and add-ins) are unaffected.
- `ActionPage` still accepts pinned shortcuts (`shortcutId`), but serialized `page` payloads are only accepted
  from Scene itself. Shortcuts and favorites created by the app continue to work.
- `ReceiverShortcut` is no longer exported. Pinned shortcut callbacks (created by Scene) still work.
- Cross-app unfreeze through `SceneFreezeProvider` now verifies that the `source` package really belongs to
  the calling UID, and only acts on installed packages.
- Cleartext HTTP is disabled by default; only the official Scene domains are exempted. Update checks now use
  the GitHub Releases API over HTTPS.

## Project structure

```
app/       Main application (activities, fragments, Compose UI, services, overlays, assets)
common/    Shared base library (root shell, file helpers, dialogs, theming)
krscript/  Script engine and dynamic page renderer (kr-script)
docs/      Project website (GitHub Pages)
```

## Development

See [AGENTS.md](AGENTS.md) for architecture notes, coding conventions, mandatory security rules and the
Material 3 UI roadmap. In short:

- Keep the code Qualcomm-only, comments and logs in English.
- New user-facing strings go to `values/strings.xml` and `values-in/strings.xml`.
- Never interpolate untrusted input into root shell commands; use `com.omarea.utils.ShellSafety`.
- Run `.\gradlew :app:compileDebugKotlin` before committing.

## Credits

- Original Scene / vtools by [omarea](https://github.com/omarea) and [helloklf](https://github.com/helloklf).
- English fork base: [ramabondanp/vtools_en](https://github.com/ramabondanp/vtools_en).
- This fork: [fannndi/Scene-Re](https://github.com/fannndi/Scene-Re).

## License

GNU General Public License v3.0 - see [LICENSE](LICENSE).
