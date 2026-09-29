# AGENT-RND — LLM/agent workflow on this repo

Everything an agent needs to inspect, drive and verify the app on the device.

## Device tools (host side)

| Command | Purpose |
|---|---|
| `bash tools/scene-debug.sh [logcat_lines]` | full runtime snapshot (profiles, freqs, daemons, thermal, logcat) as Markdown |
| `bash tools/ui-map.sh [screen]` | (re)generate `docs/ui/<screen>.png` + `.json` (elements + tap centers) |
| `bash tools/agent-tap.sh <screen> <selector>` | tap an element: text substring or `id:part` |
| `python3 tools/ui-map-md.py` | rebuild `docs/UI-MAP.md` from the JSON maps |

Screens: `home tuner tools diagnostics tweaks cpucontrol appdetails appconfig`.

```
./gradlew assembleDebug                 # compile check
./gradlew :app:testDebugUnitTest        # pure-logic tests (no device)
./gradlew assembleRelease               # APK
adb install -r app/build/outputs/apk/release/*.apk
```

## In-app introspection

- **Tools ▸ Diagnostics ▸ Generate** writes an LLM-ready Markdown+JSON report
  under `/sdcard/Android/data/com.omarea.vtools/files/debug/`
  (device, root, profile engine & daemons, CPU/GPU/thermal/mem/battery,
  recent shell executions, logcat). Pull the newest file and grep it.
- `ShellLog` (inside that report) records the exact apply blocks, direct vs
  shell ops and verification results.

## Layout map rules

- Coordinates in `docs/ui/*.json` are for **1080×2400**; regenerate the map if
  the UI changed (`bash tools/ui-map.sh`).
- Prefer `agent-tap.sh` over hard-coded coordinates; selectors match
  `text`, `content-desc` or `id:`.
- Heavy screens (tweaks, diagnostics) need ~10 s after opening before the
  dump is complete (the generator waits for that).

## Quick recipes

```
# switch profile to balance and verify
bash tools/agent-tap.sh tuner "balance"
bash tools/scene-debug.sh | grep -A4 "CPU 0-5"

# read the last apply from the diagnostics report
$ADB pull "$($ADB shell ls -t /sdcard/Android/data/com.omarea.vtools/files/debug/*.md | head -1)" /tmp/diag.md
grep -A3 "ProfileApplier" /tmp/diag.md | tail -6
```
