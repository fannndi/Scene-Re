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

The shell layer has pure, device-free unit tests worth extending whenever parsers or validators
change: `FrameworkStatsParserTest` (dumpsys/`proc` parsing, fixtures captured from the real device)
and `FrameworkControlValidationTest` (command/option injection rejection, package-name shape, the
numeric standby-bucket mapping). Prefer adding a fixture captured from the phone over an invented
one - every ROM-specific parsing bug found so far was caught that way.

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
                       ActivityAppControl is the framework-control screen (Compose, Material 3)
    fragments/         Fragments (FragmentHome and FragmentCpuModes are Compose-based)
    scene_mode/        Dynamic scene/performance mode engine, freezing, timing tasks
    privilege/         PrivilegeManager (tier authority) and ShellCapabilityProbeImpl (capability probe)
    library/shell/      Sysfs/root shell helpers (CPU, GPU, battery, thermal, FPS)
                        FrameworkStats (+Parser) reads dumpsys-backed stats when sysfs is denied;
                        MonitorStatsProvider picks the source per capability;
                        FrameworkAppControl (+Validation) wraps appops/doze/standby/permission control
    library/device/     Device-specific helpers (battery capacity, GPU info)
    store/             Persisted state (SharedPreferences wrappers, SQLite stores, ObjectStorage)
    data/              Event bus, publishers (battery/screen), background curves
    ui/                Custom views (charts), adapters, Compose overview menu
    utils/             Misc utilities (ShellSafety, Update, WindowCompatHelper, ...)
    xposed/            Xposed module hooks (optional add-on component)
  src/main/assets/     powercfg scheduling profiles (Qualcomm platforms), kr-script pages, addin scripts
common/                Shared base (KeepShell, FileWrite, dialogs, blur, themes)
                       shell/ also holds ShellCapability + ShellCapabilityRegistry (the capability model)
krscript/              Script engine: page config parsing, WebView bridge, background task notifications
```

## Scope and feature policy

- **Qualcomm only.** Do not add vendor-specific code for MediaTek, Exynos, Kirin or Unisoc.
  Device detection is `PlatformUtils.getCPUName()` (`ro.board.platform`). GPU helpers are Adreno/kgsl only.
- **Target device is the POCO X3 NFC (surya, SM7150-AC / Snapdragon 732G).** The ROM reports
  `ro.board.platform=sm6150`, so `assets/powercfg/sm6150` is the active scheduling profile and other
  platform profile directories were removed. Do not re-add profiles for other SoCs.
  Frequency limits: little cluster max `1804800` kHz, big cluster max `2304000` kHz. GPU (Adreno 618)
  pwrlevel bins go up to `825000000` Hz (800/700/610 MHz on other bins) - always read the runtime table
  from `/sys/class/kgsl/kgsl-3d0/{num_pwrlevels,available_frequencies}` instead of hardcoding it.
  Kernel branches: `LA.UM.8.9.r1-09300-SM6xx.0` (Android 10) and `LA.UM.9.1.r1-06700-SMxxx0.0-1` (Android 11+).
- The **swap-controller** feature was removed. Do not reintroduce swap/ZRAM module management UI.
  `MemoryBoostUtils.forceKswapd()` (memory reclaim) is the only remaining piece of that area.
- Supported UI languages are English and Indonesian. New user-facing strings must be added to
  `values/strings.xml` (English) and `values-in/strings.xml` (Indonesian). Never hardcode user-visible text.
- Do not add new external repositories/dependencies without a clear need; keep the dependency set small.

## Privilege tiers (Root / Shizuku / Non-root)

Every shell command is routed through `com.omarea.common.shell.ShellModeProvider`:

| Tier | Shell backend | Typical uid |
| --- | --- | --- |
| `ROOT` | `su` via `ShellExecutor.getPrivilegedRuntime()` | 0 |
| `SHIZUKU` | shell hosted by `ShizukuShellService` (Shizuku user service) | 2000 |
| `NON_ROOT` | app's own `sh` | app uid |

- `PrivilegeManager` (`app/.../vtools/privilege/`) owns detection, persistence
  (`SpfConfig.GLOBAL_SPF_PRIVILEGE_TIER`), the Shizuku permission request and the user service binding.
  It registers itself as `ShellModeProvider.shizukuShellProvider` from `Scene` (`Application`).
- UI entry point: `ActivityPrivilege` (Features tab -> Privilege mode).
- Rules for new code:
  - Never call `Runtime.exec("su")` directly; use `KeepShell`/`KeepShellPublic`/`ShellExecutor` so the
    tier is respected. The only exception is `PrivilegeManager.probeRoot()`, which detects root.
  - **Gate features on `ShellCapabilityRegistry.supports(...)`, not on `hasRootAccess`.** See the
    capability engine below. `hasRootAccess` (truly uid 0) and `isPrivileged` (root or Shizuku) are
    still correct for coarse decisions, but per-feature checks belong in the capability model.
  - `CheckRootStatus.lastCheckResult` reflects the root probe only; do not use it as a generic gate.
  - Anything that changes `shizukuAvailable` / `shizukuPermissionGranted` **must call
    `PrivilegeManager.applyMode()`**. Those flags decide `effectiveTier`, and `ShellModeProvider.mode`
    must follow; if it does not, the app logs "Shizuku available=true" while every command still runs
    through the old backend. `KeepShell` caches one process per mode and restarts on a mode change.
  - The Shizuku user service is instantiated by name: keep `ShizukuShellService` public with its
    `@Keep` constructors, keep the reserved AIDL transaction ID (`destroy() = 16777114`) and keep the
    ProGuard rules in `app/proguard-rules.pro` (`ShizukuShellService`, `IShizukuShellService*`).
  - The `ShizukuProvider` declaration in the manifest must keep `authorities="${applicationId}.shizuku"`.

### The capability engine (how to decide whether a feature can work)

Shizuku binds **asynchronously** - the binder arrives about a second after init and the user service
about 1.3 seconds after that. The app's own `sh` and the Shizuku shell have different uids and
different SELinux domains, so a shell started too early is silently the wrong backend. Two helpers
exist for this:

- `PrivilegeManager.awaitTierSettled()` waits for the configured tier's backend before probing. It
  waits on `tier` (what the user selected) rather than `effectiveTier`, because `effectiveTier`
  reports NON_ROOT while Shizuku is still starting up.
- `ShellCapabilityProbeImpl` (`app/.../privilege/`) measures what the resolved tier can actually do,
  through a single read-only script. `ShellCapability` and `ShellCapabilityRegistry` live in `:common`
  so `:krscript` can query them without depending on `:app`.

Probe answers are triple-valued, and the distinction is user-facing:

| Answer | Meaning |
| --- | --- |
| `CAP_OK` | Works right now. |
| `CAP_DENIED` | The node exists but this tier is refused; root would unlock it. |
| `CAP_NONE` | This ROM has no such control at all. |

**`[ -e ]` is not a usable existence test on these paths.** `ls /sys/class/kgsl/` is refused at shell
uid, the kernel refuses the `stat` behind `[ -e ]`, and `[ -e ]` therefore returns false for a
directory that plainly exists - which would report `CAP_NONE` ("the ROM has no GPU control") when the
truth is `CAP_DENIED` ("root would unlock it"). Existence is resolved by walking up to the nearest
listable ancestor and grepping for the next segment; see `emitExistence()`.

The probe logs `tier=`, the available/unavailable sets, and `probe_uid=` (the uid the shell actually
ran as) under the `SceneCapability` tag, so a routing mismatch is distinguishable from a real denial.

### Verified sysfs access on surya (MIUI 13, Android 12, shell uid 2000)

Measured on a POCO X3 NFC with `adb shell`; use it to decide which features can work without root:

| Path | Shell (Shizuku) | Root |
| --- | --- | --- |
| `/proc/stat`, `/proc/meminfo` | read | read |
| `/sys/devices/system/cpu/cpu*/cpufreq/*` | read | read/write |
| `/dev/cpuset/*/cpus` | read | read/write |
| `/sys/class/thermal/thermal_zone*/temp` | read | read/write |
| `/sys/class/kgsl/kgsl-3d0/*` (GPU) | denied | read/write |
| `/sys/class/power_supply/battery/*` | denied | read/write |
| `/sys/module/cpu_boost/parameters/*` | denied | read/write |
| `/sys/module/msm_performance/parameters/*` | denied | read/write |
| `/sys/block/sda/queue/read_ahead_kb`, devfreq `cpubw/*` | denied | read/write |
| `/sys/module/msm_thermal/*` | **does not exist on this ROM** | n/a |

Consequences: monitoring (CPU, thermal, memory) works in the Shizuku tier, but every powercfg write,
GPU control and battery current read requires root.

### Showing a feature that needs root (Features tab)

Root-gated entries are **visible and clickable** in every tier; they are never hidden or disabled.
`OverviewMenu` exposes the tab model as a top-level `overviewSections` val - the single source of
truth - and each `OverviewNavItem` carries `requiresRoot`. When root is absent, `SceneNavCard` gets a
`badge` ("Requires root") while staying `enabled`, so the click reaches `FragmentNav.handleNavClick`,
which shows `R.string.menu_root_required_message` naming the feature.

Rules:

- Keep `requiresRoot` in `OverviewMenu.overviewSections` and `rootRequiredIds` in `FragmentNav` in
  sync; they gate the same features from two sides. `rootRequiredIds` means "needs root", not "is
  hidden".
- Never resolve a card's title from a second, hand-maintained list - use `overviewNavTitleRes(id)` so
  the message always matches the label the user tapped.
- Distinguish "needs root" (this affordance) from `CAP_NONE` (the ROM has no such control). They point
  the user at different remedies and must not share wording.

The **framework** is fully reachable at shell uid, which is what makes Shizuku mode useful. Verified
working reads: `dumpsys` (cpuinfo, meminfo, gfxinfo, activity, battery, deviceidle), `am
get-standby-bucket`, `cmd appops get`, `pm`/`dumpsys package` permissions. Verified working writes:
`cmd appops set` and `am set-standby-bucket`. `FrameworkAppControl` wraps these;
`ActivityAppControl` is the UI.

Do not gate sysfs *write* features on anything but `ShellCapability.*_WRITE`, and do not retry a read
the kernel denied on a timer - it emits an SELinux audit line per attempt and floods the log.
`FrameworkStats` keeps a `disabledSources` circuit breaker for exactly this; reset it when the tier
changes.



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
- Debug builds expose `AgentDebugActivity` (`app/src/debug/`), a diagnostics screen for AI agents:
  ```powershell
  adb shell am start -n com.omarea.vtools/.debug.AgentDebugActivity
  adb shell cat /sdcard/Android/data/com.omarea.vtools/files/agent-debug-snapshot.json
  adb logcat -s SceneAgent
  ```
  It renders/writes a JSON capability snapshot (app, device/SoC, root, accessibility, GPU, CPU topology,
  scene config, memory) and can dump the logcat buffer. It must never modify system state.
- `AgentDebug.log(event, detail)` emits structured `SceneAgent` log lines for USB-driven diagnostics.
- Starting Shizuku over USB on the target device (Shizuku 13, no root): use "Start by connecting to a
  computer" in the Shizuku app, or derive the same command:
  ```powershell
  $apk = (adb shell pm path moe.shizuku.privileged.api).Replace("package:", "").Trim()
  adb shell ($apk -replace "base.apk", "lib/arm64/libshizuku.so")
  ```
  Then grant the API permission once in Scene -> Features -> Privilege mode (Shizuku -> Grant permission).
  Verified on surya: the user service process runs as uid `shell` with SELinux context `u:r:shell:s0`.
- For future USB-debug tooling: prefer `adb shell`-driven diagnostics and instrumentation tests under
  `app/src/androidTest` over in-app hidden debug screens.
- Debug helpers must be part of the debug build type only (e.g. `debugImplementation` or `BuildConfig.DEBUG`).

## Commit style

- Imperative English subject, e.g. `Fix root shell injection in SceneFreezeProvider`.
- One logical change per commit. Do not commit generated files or build outputs.
- Never commit or push unless the task explicitly asks for it.
