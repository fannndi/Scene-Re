# AGENTS.md — Scene

## What this project is

Scene is a rooted Android performance/gaming app (Kotlin/Java) focused exclusively on **Xiaomi phones with Qualcomm Snapdragon SoCs**. Root-only: **no Xposed, Zygisk, LSPosed, or vaddin** integration.

## Hard scope rules

Do **not** reintroduce:

- Non-Qualcomm SoC support (MediaTek/MTK, Exynos, Kirin, Mali GPU paths, `/proc/ppm`, GED)
- Non-Xiaomi brand feature pages (OPPO, Flyme/Meizu, vivo, Samsung-specific UI)
- Xposed module code (`com.omarea.xposed`, `xposed_init`, XposedBridge API, `SceneFreezeProvider`/`SceneUnfreezeProvider` launcher hooks, `XposedExtension`/vaddin AIDL)
- Device spoof templates for non-Xiaomi brands (Xiaomi templates in `configs.xml` are OK)

Safe to keep: Magisk (root) scripts/modules, AOSP-generic kr-script pages, Qualcomm + MIUI/HyperOS features.

## Layout

- `app/` — main app; assets in `app/src/main/assets/`
  - `powercfg/<platform>/` — per-SoC profiles; platform dir name must match `ro.board.platform` (qcom only: kona, lahaina, taro, sdm*, sm*, msm*, lito, universal…)
  - `kr-script/` — script pages; menu root is `kr-script/more.xml` (wired via `kr-script.conf`)
  - UI: `app/src/main/java/com/omarea/vtools/`
- `common/` — shell/root helpers (`KeepShellPublic`, `KernelProrp`, …)
- `krscript/` — script engine module
- `mi-thermal-config/` — Xiaomi thermal presets
- `swap-controller/` — swap module
- `others/` — scratch/dev files only

## Conventions

- Device/SoC detection: `PlatformUtils.getCPUName()` reads `ro.board.platform`; Xiaomi check via `Build.MANUFACTURER == "XIAOMI"`.
- GPU is Adreno/kgsl only (`GpuUtils`); `supported()` = Adreno.
- Strings in `app/src/main/res/values/strings.xml`; arrays (powercfg app lists, device templates) in `configs.xml`.
- Shell scripts run through kr-script executor; page visibility via `visible="run common/*.sh"`.
- JSON under `powercfg/` must stay valid (validate with `ConvertFrom-Json` or `jq`); UTF-8 without BOM.
- profile.json `platform` field must equal the directory name.

## Build & verify

```powershell
# keystore.properties must exist (copy from keystore.properties.example) or Gradle config fails
./gradlew assembleDebug
```

Before finishing a change:

1. `./gradlew assembleDebug` succeeds (catches broken viewBinding IDs and missing strings).
2. Grep for regressions: `xposed|vaddin|exynos|isMTK|/proc/ppm|kr_flyme|kr_mtk|kr_oppo|kr_vivo` should only hit historical docs if anything.
3. Do not edit `.gitignore`-tracked secrets; `keystore.properties` and `*.keystore` stay untracked.

## Key files

- Mode switching: `app/src/main/java/com/omarea/scene_mode/ModeSwitcher.kt`, `CpuConfigInstaller.kt`
- CPU/GPU control: `library/shell/CpuFrequencyUtils.java`, `GpuUtils.java`, `activities/ActivityCpuControl.kt`
- Kr-script menu: `assets/kr-script/more.xml` + `assets/kr-script.conf`
- Freeze (suspend-only): `activities/ActivityFreezeApps.kt` (no Xposed path)
