# Scene — Debug / Test / Verification Workstream Summary

Scope: Parts **A–E** of the debugging-and-verification directive.
Baseline: `Scene_5.0_OpenSource_r1806`. Target: Xiaomi + Qualcomm Snapdragon, API 29–31 (Android 10–12), arm64.

> **Status caveat:** everything below is **compile-verified and unit-verified**. The instrumented
> suite has **never executed** — no device was attached (`adb devices` returned only the header).
> Any on-device claim in this document is explicitly marked as pending.

---

## Part A + C — Structured logging and error reporting

New single logging layer: `app/src/main/java/com/omarea/utils/SceneLog.kt` (~262 lines).

Four sinks per record, every one `runCatching`-guarded so logging can never itself throw:

| Sink | Backing | Bound |
|---|---|---|
| logcat | tag prefix `Scene:` | — |
| memory ring | `CopyOnWriteArrayList` | 2000 records |
| listeners | registered callbacks | — |
| file | rotating, 1 × `.1` generation | 512 KB |

Public surface: `init(context)`, `i/w/e/d`, `testResult(feature, passed, detail)`,
`trace(tag, what, block)`, `recent`, `dump(lines)`, `logFilePath`, `clear`,
`addListener` / `removeListener`, `setFileLoggingEnabled(enabled)`.

File logging is gated on `SpfConfig.GLOBAL_SPF_SCENE_LOG`, which the existing
**Settings → Debug layer** switch in `ActivityOtherSettings.kt` now drives.

Wiring points:

- `Scene.kt` — `SceneLog.init(this)` runs **before** `CrashHandler().init(this)`, then logs a
  boot line with API level / model / device.
- `CrashHandler.kt` — reports through `SceneLog.e("Crash", …)` first, then the legacy file write,
  then dumps the in-memory buffer to `scene-log-crash.txt` before the process dies.
- `AccessibilityScenceMode.kt` — the on-screen floating log view now subscribes via
  `SceneLog.addListener { … dump(OVERLAY_LOG_LINES) }` (40 lines) and deregisters in `destroy()`.

---

## Part B — Test infrastructure

### Unit tests — **103 tests, all passing**

| Suite | Tests | Covers |
|---|---|---|
| `common/shell/ShellEscapeTest.kt` | 35 | shell quoting / metachar suppression |
| `library/shell/ProcessFilterTest.kt` | 26 | process classification |
| `utils/SceneLogTest.kt` | 20 | ring bound, levels, listeners, dump |
| `library/shell/FreqFormatterTest.kt` | 14 | frequency formatting |
| `library/calculator/FlagsTest.kt` | 8 | bit-flag arithmetic |

**Verified good:** 0 failures, 0 skipped.

### Instrumented tests — **50 checks, compile-verified, NOT executed**

- `androidTest/.../EnvironmentInstrumentedTest.kt` — 17 checks (device/SoC/root/Adreno/API).
- `androidTest/.../FeatureVerificationInstrumentedTest.kt` — 33 checks (CPU clusters, GPU,
  thermal, freeze, kr-script assets, powercfg JSON validity…).
- `androidTest/.../RootFileTestProbe.kt` — read-only probe helper, 0 tests.

Design rule applied throughout: **read-only**. A suite that mutates live kernel state is
untrustworthy, so the tests probe and assert plausibility rather than writing.

Shared verdict pattern:

```kotlin
private fun verdict(feature: String, ok: Boolean, detail: String) {
    SceneLog.testResult(feature, ok, detail)
    assertTrue("$feature: $detail", ok)
}
```

Toolchain: androidx.test core 1.6.1 / runner 1.6.2 / rules 1.6.1 / ext-junit 1.2.1 /
espresso 3.6.1 — the last set still compatible with `minSdk 29` (1.7.x raised the floor above 29).

---

## Part D — LLM agent over USB debugging

`scripts/scene-adb` (~600 lines) — the agent's entire interface.

```
doctor   build   install   test   verify   logs   watch   features   all
```

- `--json` for machine consumption.
- Distinct exit codes: `2` adb missing · `3` no device · `4` scope violation ·
  `5` build failure · `6` install failure · `7` test failure · `8` usage.
- Probes known adb locations, honouring `$ADB`.
- Clears logcat before `verify` so verdicts are attributable to the current run.

**Reporting contract** — the LLM reads per-feature outcomes without parsing JUnit XML:

```
Scene:Test: SCENE_TEST <PASS|FAIL> <feature-id> - <detail>
```

The parser anchors on the **tag**, not the marker string. This matters: any process can print
the literal text `SCENE_TEST`, so matching only the marker yields false verdicts. This was a
real bug, caught by the self-test:

```bash
[[ "$line" == *"Scene:Test:"*"$TEST_MARKER "* ]] || continue
status="${line#*$TEST_MARKER }"; status="${status%% *}"
[[ "$status" == "PASS" || "$status" == "FAIL" ]] || continue
```

`scripts/scene-adb-selftest` exercises that parser against eight fixture classes — **10 checks,
all passing**.

`docs/usb-debugging.md` documents the command table, exit codes, reporting contract, feature-id
table, the agent pseudocode loop, and troubleshooting.

---

## Part E — Dead-code re-examination and rewiring

### Applied (low-risk)

| Item | Change | Why it was safe |
|---|---|---|
| `auto-skip-config-v1.json` | moved from repo root → `app/src/main/assets/addin/`; BOM stripped; CRLF→LF | previously an orphan; a failed cloud fetch left the store **empty**, so the feature silently no-op'd offline. Now seeds from the bundled asset. |
| `img/bootdevice.sh` | added the missing `verify` branch | without it the new page's `visible="run img/bootdevice.sh verify"` gate was meaningless |
| `more.xml` | new group wiring the orphaned TWRP/OTA page via `config="img/img.xml"` | page existed with no entry point |
| `ActivityAddin.kt` | wired `DexCompileAddin(context).modifyConfig()` as a second list item | class was previously unreachable |
| `KrScriptConfig.java` | `PAGE_LIST_CONFIG_DEFAULT` corrected from non-existent `kr-script/pages/more.xml` → `kr-script/more.xml`; removed 4 dead getters + orphaned constants | **real bug** — the default page list path was broken |

### Real bugs fixed (found by the audit)

1. **`ProcessFilter.isAndroidProcess` never matched `system_server`.**
   The old heuristic required a dot in the process name. But `ProcessInfo.command` is the
   `ps -e -o …,COMMAND,CMDLINE` column = **argv[0]**, which for zygote-forked processes is
   `/system/bin/app_process` / `app_process64` — and `system_server` has an **underscore, no dot**.
   The *System applications* filter therefore could not show the most prominent system process.
   Fixed with an `app_process[0-9]*` regex plus an explicit runtime-thread set.
   *Premise verified before touching production code* (confirmed `ps -o COMMAND` output first).

2. **`bootdevice.sh` missing `verify` branch** (above).

3. **`PAGE_LIST_CONFIG_DEFAULT` wrong path** (above).

### Deliberately NOT applied — and why

| File | Verdict |
|---|---|
| `others/kr-script/more.xml` | **mock fixture** — 353 B vs the live 2555 B. Not a rewire candidate. |
| `others/test/AdrenoGPU*.sh` | Adreno **read-only** probes + the same `msm-adreno-tz` write live `powercfg/*/powercfg-utils.sh` already does per-platform. **Superseded.** |
| `others/test/ExtPerformance.sh` | generic `/sys/class/devfreq` sweep with **no backup at all**. Unsafe. |
| `others/test/ExtPerformance2.sh` / `3.sh` | generic devfreq backup/restore writing `/cache/governor_backup.prop`. It sweeps **every** devfreq node — many are ROM-critical (e.g. `soc:qcom,cpu-llcc-ddr-bw`). Live code deliberately only touches Adreno's own node (`kgsl-3d0/devfreq`). **Not safe as-is.** |
| `others/powercfg/general_optimize.sh` | cpuset/stune pinning; live powercfg already has its own devfreq + cpuset handling. **Superseded.** |
| `others/extreme_power.sh` | no live equivalent, but it disables Wi-Fi/data and force-stops+suspends all apps; `ActivityFreezeApps` already covers freeze/suspend safely. **Superseded.** |
| `others/verify_shell_escape.sh` | already mirrored by `ShellEscapeTest.kt` (35 tests). **Superseded.** |

**Conclusion: `others/` stays as staging.** Nothing in it is a safe drop-in.

### The one genuine rewire candidate — `others/fps-chart/`

A complete offline Vue 2 + Chart.js 2.7.2 FPS-chart front-end (~737 KB, including a vendored
`vue.min.js` and `Chart.bundle.2.7.2.js`). Its only missing piece is a `window.SceneJS` bridge:

```javascript
const SceneJS = window.SceneJS;
    toolbarOn: SceneJS ? JSON.parse(SceneJS.getFpsToolbarState()) : false,
    sessions:  SceneJS ? JSON.parse(SceneJS.getSessions()) : [],
    device:    SceneJS ? JSON.parse(SceneJS.getDeviceInfo()) : {}
    SceneJS.toggleFpsToolbar(!this.toolbarOn)
    SceneJS.deleteSession(session.sessionId)
    ...(JSON.parse(SceneJS.getSessionData(session.sessionId)))
```

`grep -rn "SceneJS" app/src common/src krscript/src` → **0 hits**. The bridge was lost.

**Every datum it needs already exists** in `FpsWatchStore`: `sessions()`,
`sessionFpsData/sessionTemperatureData/sessionCpuLoadData/sessionGpuLoadData/sessionCapacityData`,
`sessionAvgFps/MinFps/MaxFps`, `deleteSession(id)`. And `FloatFpsWatch` exposes
`show`, `showPopupWindow()`, `hidePopupWindow()` for the toolbar toggle.

**The mechanism gap:** `grep -rn "localHtmlPage"` → **0 hits**. `PageNode` has `pageConfigPath`
(`config=`), `pageConfigSh`, `onlineHtmlPage` (`html=`), `link`, `activity` — but **no
offline/local-html field**. So delivery must be built.

The safe route: `ExtractAssets.extractResources("fps-chart")` already walks an asset directory
outside-in and sets the readable/executable bit per file, so the files land in `filesDir`
ready to serve — then open a `file://` URL through `ActionPageOnline`, which already passes
`credible = url.startsWith("file:///android_asset")` into `WebViewInjector`.

**Not applied unilaterally.** This is a multi-file feature (new bridge class + a new page
mechanism or `PageNode` field + moving assets), not a low-risk one-liner. The standing
instruction was *"Terapkan yang aman"* (apply what is safe) — so this is presented as a
scoped proposal for approval.

---

## Housekeeping findings

- `androidxTestMonitor` is declared in `gradle/libs.versions.toml` (line 79, `1.7.2`) but
  **never referenced** in `app/build.gradle` (0 hits). Unused catalog entry.
- `androidxTestEspresso`, `androidxTestRules`, `androidxTestCoreKtx` are wired into
  `app/build.gradle` but have **zero direct source references** — imported transitively, not used.
- The four deleted kr-script files (`android_o.sh`, `vendor_boot_rec.sh`, `key_code.sh`,
  `thread_count_options.sh`) and the deleted classes (`DDRUtils`, `MyChatView`,
  `StrokeTextView`) all have **0 live references** — the sole `vendor_boot_rec.sh` hit is a
  commented-out line in `twrp_mode.sh:9`, i.e. historical, not a dangling dependency.
- **Regressions grep is clean:** no hits for
  `xposed|vaddin|exynos|isMTK|/proc/ppm|kr_flyme|kr_mtk|kr_oppo|kr_vivo|ActivityMiuiThermal|DialogCustomMAC|DialogAddinModifyDevice|ActivityModules|device_templates`.

---

## Outstanding

**On-device verification has not run.** `adb devices` shows only the header; no device attached.

Once a Xiaomi device is connected with USB debugging enabled:

```bash
scripts/scene-adb all      # doctor → build → install → test → verify
```

The artifacts are already built:
`app/build/outputs/apk/debug/Scene_5.0_OpenSource_r1806_debug.apk`
`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
