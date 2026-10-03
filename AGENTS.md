# AGENTS.md — Scene-Re

Android performance-tuning app for rooted devices. Modified fork of
[Scene5 OpenSource / helloklf/vtools](https://github.com/helloklf/vtools)
(GPL-3.0), rebranded to **Scene-Re**. Personal build, fully offline.

## Quick orientation

| Layer | Path | Notes |
|---|---|---|
| App module | `app/` (`com.omarea.vtools`) | UI (partial Compose + Miuix), features (scene mode, CPU, freeze, charge, thermal editor) |
| Common module | `common/` (`com.omarea.common`) | `KeepShell` (persistent root shell), shared UI helpers |
| Script engine | `krscript/` (`com.omarea.krscript`) | kr-script engine + web UI |
| Tuning profiles | `app/src/main/assets/powercfg/` | per-SoC powercfg scripts |
| kr-script pages | `app/src/main/assets/kr-script/` | per-platform scripts + XML page definitions |
| Addin scripts | `app/src/main/assets/addin/` | one-shot shell actions |
| Thermal tool | `mi-thermal-config/` (Go) | encrypt/decrypt MIUI thermal configs (standalone) |
| Docs | `docs/` | MIUI thermal notes, screenshots |

## Identity / rebrand facts

- **applicationId**: `com.fannndi.scenere`; Java/Kotlin packages stay `com.omarea.*`.
- **Shell system properties** `vtools.*` are kept on purpose (runtime contract
  between Kotlin code and dozens of shell scripts). Do not rename casually.
- **Keep in sync when changing identity**: runtime references to the app
  package live in more places than `build.gradle`:
  - `app/src/main/assets/powercfg/*/*.sh` (process pinning)
  - `app/src/main/assets/custom-command/*.sh` (`am startservice -n <pkg>/...`)
  - `app/src/main/assets/kr-script/miui/miui-thermal.xml` (activity reference)
  - `com.omarea.vtools.services.CompileService` (generated `am broadcast -n <pkg>/...`)
  - `com.omarea.xposed.XposedInterface` (`case "<pkg>":` active check)
  - `AutoSkipAd`, `ProcessUtils`, `ProcessUtilsSimple`, `FragmentCpuModes`
  - `AndroidManifest.xml` provider authorities + `AppFreezeInjector` content URI

## Build

```
./gradlew :app:assembleDebug        # debug APK
./gradlew :app:assembleRelease      # signed release APK
```

- SDK: `/home/fannndi/Android/Sdk` (compileSdk 36, build-tools 36.0.0,
  NDK 21.0.6113669, CMake 3.22.1). `local.properties` is required.
- Signing: `keystore.properties` at repo root (gitignored; see
  `keystore.properties.example`). JKS: `scenere.jks`, alias `scenere`.
- `versionCode` = git commit count — the repo must have at least one commit.
- Device: POCO X3 NFC (surya); `adb` at `~/Android/Sdk/platform-tools/adb`.

## Hard rules

1. The app is **offline by design** — do not reintroduce the update checker,
   "Scene-Online" script browser or cloud auto-skip configs.
2. Identity changes must be verified with grep, not just a successful build:
   `grep -rn "com.omarea.vtools\|vtools.omarea.com\|helloklf.github.io"` in
   `app/src/main` should only match intentional leftovers (e.g. `com.omarea.*`
   Java packages, upstream doc links in comments).
3. After any change touching `build.gradle`, assets or manifest, run
   `./gradlew :app:assembleDebug` before committing.
4. Keep commits small and scoped (rebrand step per area); the baseline
   commit `87a3d89` is the pre-rebrand reference.
5. GPL-3.0 obligations: keep `LICENSE`, `NOTICE` and upstream attribution.
