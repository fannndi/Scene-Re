# AGENTS.md

Guidance for AI coding agents working in this repository (Scene-Re).

## What this project is

Scene-Re is an open-source Android root/system tuning tool (a fork of Scene / vtools).
It targets **Qualcomm Snapdragon** devices (`ro.board.platform`), requires root, and uses a persistent `su`
shell to read/write sysfs, manage app freezing, scheduling profiles, thermal controls, and FPS/CPU/GPU monitoring.

- Application ID: `com.omarea.vtools`
- Modules: `:app` (main app), `:common` (shared utilities), `:krscript` (script engine + page renderer)
- Languages: **English (default) and Indonesian (`values-in`) only**. All code comments must be English.

## Build & verification

Prerequisites:

- JDK 17-25 (Android Studio JBR 21/25 works). Gradle 9.1 requires Java <= 25.
- Android SDK with `compileSdk 36`, build-tools 36, NDK `28.2.13676358`, CMake (AGP default).
- `local.properties` with `sdk.dir=...` (git-ignored) or `ANDROID_HOME` set.
- `keystore.properties` is **optional**. Without it, release variants are unsigned; debug builds work.

Commands (PowerShell):

```powershell
# Set JDK if needed
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"

# Fast compile check after code changes
.\gradlew :app:compileDebugKotlin

# Full debug APK
.\gradlew :app:assembleDebug

# Release APK (R8 enabled; unsigned without keystore.properties)
.\gradlew :app:assembleRelease

# Unit tests
.\gradlew test
```

Output APKs: `app/build/outputs/apk/<variant>/Scene_5.0_OpenSource_r<versionCode>_<variant>.apk`.
`versionCode` is derived from `git rev-list HEAD --count` (falls back to `1` when git history is unavailable,
e.g. shallow clones).

Always run `:app:compileDebugKotlin` (or `assembleDebug`) after changes and fix all `e:` / `error:` output.
Do not commit `build/`, `.gradle/`, `.kotlin/`, `local.properties`, or `keystore.properties`.

## Repository layout

```
app/                   Main application (activities, fragments, Compose UI, dialogs, services, overlay windows)
  src/main/java/com/omarea/
    activities/        Activities (ActivityMain hosts 3 tabs: Features / Overview / Adjust)
    fragments/         Fragments (FragmentHome and FragmentCpuModes are Compose-based)
    scene_mode/        Dynamic scene/performance mode engine, freezing, timing tasks
    library/shell/      Sysfs/root shell helpers (CPU, GPU, battery, thermal, FPS)
    library/device/     Device-specific helpers (battery capacity, GPU info)
    store/             Persisted state (SharedPreferences wrappers, SQLite stores, ObjectStorage)
    data/              Event bus, publishers (battery/screen), background curves
    ui/                Custom views (charts), adapters, Compose overview menu
    utils/             Misc utilities (ShellSafety, Update, WindowCompatHelper, ...)
    xposed/            Xposed module hooks (optional add-on component)
  src/main/assets/     powercfg scheduling profiles (Qualcomm platforms), kr-script pages, addin scripts
common/                Shared base (KeepShell, FileWrite, dialogs, blur, themes)
krscript/              Script engine: page config parsing, WebView bridge, background task notifications
```

## Scope and feature policy

- **Qualcomm only.** Do not add vendor-specific code for MediaTek, Exynos, Kirin or Unisoc.
  Device detection is `PlatformUtils.getCPUName()` (`ro.board.platform`). GPU helpers are Adreno/kgsl only.
- The **swap-controller** feature was removed. Do not reintroduce swap/ZRAM module management UI.
  `MemoryBoostUtils.forceKswapd()` (memory reclaim) is the only remaining piece of that area.
- Supported UI languages are English and Indonesian. New user-facing strings must be added to
  `values/strings.xml` (English) and `values-in/strings.xml` (Indonesian). Never hardcode user-visible text.
- Do not add new external repositories/dependencies without a clear need; keep the dependency set small.

## Code conventions

- Kotlin for new code; Java is legacy but still present. Follow the surrounding file style.
- All comments and log messages in **English**. No emojis.
- No unused imports. Keep changes focused; avoid drive-by reformatting.
- Ranges/`%` placeholders in strings must be preserved exactly between `values` and `values-in`.
- For Compose screens, aim for Material 3 (`androidx.compose.material3`); the long-term goal is to migrate
  the remaining Miuix-based screens and XML layouts to Material 3 (see UI roadmap below).
- Keep screens small and modular: one feature per package under `com.omarea.vtools.<feature>`.
  Prefer state hoisting; avoid adding new global mutable state.

## Security rules (mandatory)

This app executes commands as root. Treat every external input as hostile.

1. Validate and quote all values that reach a shell command: use `com.omarea.utils.ShellSafety`
   (`isValidPackageName`, `isInstalledPackage`, `isValidTaskId`, `isValidMac`, `quote`).
   Never interpolate raw Intent/ContentProvider/broadcast extras into `KeepShell*`/`ShellExecutor` commands.
2. Components that are exported must validate inputs and should have `android:exported="false"` unless
   external access is a required feature. External components in use:
   `SceneFreezeProvider` (caller-UID checked), `ReceiverSceneMode`, `ActivityQuickStart`.
3. `ActionPage` accepts serialized `PageNode` payloads only with `Scene.internalIntentToken`; do not remove
   that check. Pinned shortcuts use `shortcutId` (opaque UUID) instead.
4. `ActionPageOnline`, `SceneTaskIntentService` and `ReceiverShortcut` are intentionally **not exported**.
   Do not re-export them.
5. Prefer `PendingIntent.FLAG_IMMUTABLE`; register runtime receivers with `ContextCompat.registerReceiver`
   and an explicit export flag.
6. Cleartext HTTP is limited to official domains via `res/xml/network_security_config.xml`. Use HTTPS for
   new endpoints.

## UI & UX roadmap (Material 3)

- `ActivityMain` hosts three tabs: Features (`FragmentNav`, Compose `OverviewMenu`), Overview (`FragmentHome`,
  Compose + `AndroidView` charts), Adjust (`FragmentCpuModes`, Compose + `AndroidView`).
- Current theming mixes AppCompat XML themes, custom views and Miuix (`top.yukonga.miuix.kmp`).
  Target: standard **Material 3** components with a single `SceneTheme` and design tokens for
  color/typography/spacing; remove Miuix after migration.
- When migrating a screen: keep behavior identical, wrap legacy custom views (charts, `FpsDataView`,
  `OverScrollGridView`) in `AndroidView`, and avoid changing navigation semantics.
- Screen modules should expose a `Screen()` composable plus a state holder; no shell calls on the main thread.

## AI-agent debugging workflow

- Ground truth for issues is `logcat`. `ActivityOtherSettings` exposes "Error log" (logcat dump) and the
  app has a crash handler. Keep logs English and free of secrets (no passwords, no full shell command dumps).
- `BuildConfig.DEBUG` builds keep all logs; release builds strip `Log.d`/`Log.v` via R8 rules
  (`app/proguard-rules.pro`). Add `Log.e` for errors that must survive minification.
- For future USB-debug tooling: prefer `adb shell`-driven diagnostics and instrumentation tests under
  `app/src/androidTest` over in-app hidden debug screens.
- Debug helpers must be part of the debug build type only (e.g. `debugImplementation` or `BuildConfig.DEBUG`).

## Commit style

- Imperative English subject, e.g. `Fix root shell injection in SceneFreezeProvider`.
- One logical change per commit. Do not commit generated files or build outputs.
- Never commit or push unless the task explicitly asks for it.
