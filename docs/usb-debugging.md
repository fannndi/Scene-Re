# Scene — USB debugging harness (for humans and LLM agents)

Scene's interesting features only fail on a real rooted Xiaomi/Snapdragon phone,
because they read kernel nodes that no emulator reproduces. This document
describes the harness that turns the debug loop into commands with
machine-readable output, so an LLM agent — or a human — can drive it the same way.

Nothing here needs an internet connection or a cloud service. Everything runs
over the USB cable you already have.

---

## 1. The one command that matters

```bash
scripts/scene-adb all
```

That is `doctor → build → install → test → verify`. If you only remember one
thing, remember this one. It exits non-zero the moment something is wrong, and
prints which feature failed.

---

## 2. Commands

| Command | Needs device | What it does |
|---|---|---|
| `doctor` | no* | Checks adb, device, root, SoC, Adreno, Magisk |
| `build` | no | Assembles the app APK **and** the androidTest APK |
| `install` | yes | Installs both APKs over USB |
| `test` | no | JVM unit tests (103 of them) |
| `verify` | yes | Runs the on-device suite, reports per-feature verdicts |
| `logs` | yes | Dumps `SceneLog` records from the device |
| `watch` | yes | Live-streams `SceneLog` while you use the app |
| `features` | no | Lists every feature id `verify` reports on |
| `all` | yes | The whole pipeline |

\* `doctor` runs without a device but will tell you one is needed.

### Options

```bash
scripts/scene-adb doctor --json          # machine-readable environment report
scripts/scene-adb verify --json          # machine-readable test results
scripts/scene-adb verify --class com.omarea.vtools.FeatureVerificationInstrumentedTest
scripts/scene-adb logs --lines 500
scripts/scene-adb logs --since "10 minutes ago"
```

### Exit codes

An agent should branch on these rather than parsing prose.

| Code | Meaning | What to do |
|---|---|---|
| 0 | success | — |
| 2 | adb not found | Install platform-tools, or set `ADB=/path/to/adb` |
| 3 | no device / offline / unauthorised | Plug in the phone; accept the USB debugging prompt |
| 4 | device unsupported | Not a rooted Xiaomi Qualcomm — out of scope for this project |
| 5 | build failed | Read the Gradle error |
| 6 | install failed | Try `adb uninstall com.omarea.vtools` first |
| 7 | one or more tests failed | Read the FAIL lines; each names a feature |
| 8 | bad usage | Fix the command line |

---

## 3. How results are reported

There are two layers, and both are stable contracts.

### Layer 1 — logcat, for the test harness

Every feature check calls `SceneLog.testResult(feature, passed, detail)`, which
emits a rigid line:

```
09-24 21:03:11.400 E Scene:Test: SCENE_TEST FAIL gpu.frequency - getGpuFreq()=''
```

The format is `SCENE_TEST <PASS|FAIL> <feature-id> - <detail>`, always under the
logcat tag `Scene:Test`. The harness greps exactly that tag — not merely the
marker string — so unrelated output containing the word `SCENE_TEST` cannot be
mistaken for a verdict.

Read them by hand:

```bash
adb logcat -d -s Scene* | grep SCENE_TEST
```

### Layer 2 — the app's own diagnostic log

`SceneLog` fans every record out to four sinks:

1. **logcat**, under `Scene:<tag>` — live, for `adb logcat -s Scene*`
2. **an in-memory ring**, capped at 2000 records — for the in-app overlay
3. **registered listeners** — the floating debug overlay
4. **a file**, only when the debug layer is switched on

The file lives at `Android/scene-log.txt` in the app's external files directory,
rotates at 512 KB keeping one previous generation, and is written only when the
user enables the debug layer in Settings (or `SceneLog.setFileLoggingEnabled(true)`).

Pull it:

```bash
adb shell run-as com.omarea.vtools cat /sdcard/Android/data/com.omarea.vtools/files/Android/scene-log.txt
```

If the app crashes, `CrashHandler` writes the in-memory buffer to
`scene-log-crash.txt` next to it, so the events leading up to the crash survive
the process death.

### Level letters

```
V verbose   D debug   I info   W warn   E error
```

A record looks like:

```
09-24 21:03:11.400 E Scene:Crash: uncaught exception on thread 'main'
java.lang.IllegalStateException: kaboom
    at ...
```

---

## 4. Feature ids

`scripts/scene-adb features` prints the full list. The naming is
`<area>.<check>`; the areas are `environment`, `cpu`, `gpu`, `process`, `swap`,
`krscript`, `pm`, and `root`.

A few worth knowing:

| Id | Proves |
|---|---|
| `environment.root_shell` | root works at all |
| `environment.soc_qualcomm` | `ro.board.platform` is a Snapdragon |
| `environment.logcat_visible` | the whole reporting chain works |
| `cpu.topology` | cluster/core discovery, so the CPU page renders |
| `cpu.min_max_limits` | `min <= max`, so the sliders are usable |
| `gpu.adreno_present` | Adreno detection, the gate for every GPU page |
| `process.filter_buckets` | app/system/other filters all find something |
| `process.system_server` | `system_server` classified correctly |
| `swap.zram_device` | a zram device exists to configure |
| `krscript.pages_exist` | every menu `config=` page is packaged |
| `krscript.autoskip_seed` | the offline auto-skip fallback parses |

---

## 5. Driving it from an agent

The intended loop, expressed as pseudocode:

```
1. run `scripts/scene-adb doctor --json`
     ready == "no"  ->  report `problems` to the user and stop
2. run `scripts/scene-adb build`
     exit 5         ->  show the Gradle error, stop
3. run `scripts/scene-adb install`
     exit 6         ->  suggest uninstall, stop
4. run `scripts/scene-adb verify --json`
     exit 0         ->  report passed/total
     exit 7         ->  for each id in failedFeatures:
                          run `scripts/scene-adb logs --lines 200`
                          find the SCENE_TEST FAIL line for that id
                          report the detail verbatim
5. if a failure is unclear, run `scripts/scene-adb watch`
   and ask the user to trigger the feature from the UI
```

Two things make this work well:

- **`--json` everywhere.** Steps 1 and 4 have a JSON mode, so an agent never
  has to parse coloured prose.
- **The exit code carries the verdict.** An agent does not need to understand
  the output to know whether to continue; the code tells it. `doctor` returns
  the *specific* code for its first blocking problem (2 = adb missing,
  3 = no device, 4 = device outside scope) rather than a generic failure,
  so the caller can branch on the reason instead of just the fact.

---

## 6. Current status (last updated 2026-09-24)

| Part | Status |
|---|---|
| JVM unit tests | **103 written, all passing** |
| Instrumented suite | **50 checks written**, compile-verified, **not yet run on a device** |
| Harness | implemented and self-tested (10 parser checks, all passing) |
| Device verification | **blocked — no device connected** |

`adb devices` currently shows an empty list. Everything that needs a device is
written and compiles; it has not been executed against hardware yet. Once the
phone is plugged in:

```bash
scripts/scene-adb all
```

…and the per-feature results will print.

### Breakdown

| Suite | File | Checks |
|---|---|---|
| Unit | `ShellEscapeTest` | 35 |
| Unit | `ProcessFilterTest` | 26 |
| Unit | `SceneLogTest` | 20 |
| Unit | `FreqFormatterTest` | 14 |
| Unit | `FlagsTest` | 8 |
| Instrumented | `FeatureVerificationInstrumentedTest` | 33 |
| Instrumented | `EnvironmentInstrumentedTest` | 17 |

### Why the instrumented tests cannot be "just run" blindly

They assert things that are true only on a correctly-provisioned device — root,
an Adreno GPU, a Xiaomi manufacturer string. On a wrong device they are
*expected* to fail, and the failure is the useful signal (it tells you the device
is out of scope, not that the code is broken). `doctor` distinguishes those two
cases before the suite runs.

---

## 7. Troubleshooting

**`adb: device unauthorized`**
Accept the "Allow USB debugging?" prompt on the phone. If it never appears,
revoke USB debugging authorisations in Developer Options and replug.

**`doctor` says "not connected" but the phone is plugged in**
Run `adb devices`. If the phone shows as `offline`, run
`adb kill-server && adb start-server`. On Windows, a missing or vendor-specific
USB driver is the usual cause.

**Root check fails but you know the phone is rooted**
Grant the root request when it appears on screen — the first `su` invocation on
a new shell blocks until the user taps Allow. `doctor` uses a 2-second-ish
timeout and reports "not rooted" if nothing answered.

**`verify` reports "no SCENE_TEST verdicts found"**
The suite did not run. Check `build/scene-adb/verify-instrument.txt` for the
`am instrument` output. The usual causes are a missing test APK, or the
instrumentation crashing on launch.

**A GPU check fails**
Every GPU test is gated on `gpu.adreno_present`. If that fails, the SoC is not
an Adreno one and the GPU pages are correctly disabled — this is a device-scope
problem, not a bug.

---

## 8. Files

```
scripts/scene-adb                       the harness
scripts/scene-adb-selftest              parser self-test (10 checks)
app/src/test/...                        JVM unit tests (103)
app/src/androidTest/...                 on-device suite (50 checks)
app/src/androidTest/.../RootFileTestProbe.kt   read-only device probes
app/src/main/java/com/omarea/utils/SceneLog.kt structured logging
app/src/main/assets/addin/auto-skip-config-v1.json   offline auto-skip seed
build/scene-adb/                        raw artifacts from the last verify run
```
