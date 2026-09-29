# Scene-Re (vanilla)

Device-exact performance tuning app for a single target: **POCO X3 NFC /
surya / sm6150** (MIUI 12, APatch root). Fully offline.

- Tuning engine: per-device `tuning.json` (bundled + user copy in
  `/sdcard/Scene/profiles/`), applied and verified through a root shell.
- Profiles: Powersave / Balance / Performance / Custom (+ stock `release`).
- Dynamics: per-app profiles, HWUI overrides, thermal daemon lifecycle.

Docs for contributors and AI agents:

| Document | Contents |
|---|---|
| `AGENTS.md` | orientation, hard rules, device facts |
| `docs/ARCHITECTURE.md` | module map, invariants, how-to guides |
| `docs/PROFILE-ENGINE.md` | tuning.json schema and engine behaviour |
| `docs/UI-MAP.md` | screen map with tap coordinates (agent tooling) |

Build and test:

```
./gradlew assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew assembleRelease
bash tools/scene-debug.sh        # device snapshot (adb + su)
```
