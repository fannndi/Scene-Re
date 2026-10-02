# No-root & uninstall safety ("Monitor mode" + hygiene)

Two failure modes this document covers:

1. **Root hilang** (ROM update / dirty flash / APatch removed) while Scene is
   installed — the app must not pretend to tune, must not spam dead `su`
   shells and must recover with one tap once root is back.
2. **App dihapus** — uninstalling Scene must not leave anything behind that
   keeps interfering with the ROM (frozen apps, disabled services, tweaked
   settings, modules, scripts).

## 1. Monitor mode (no root)

Single source of truth: `util/CheckRootStatus.kt`

| State | Meaning | Detection |
|---|---|---|
| `AVAILABLE` | su works | `KeepShell.checkRoot()` succeeds |
| `MISSING` | su binary cannot even be spawned | `ShellExecutor.getSuperUserRuntime()` throws → `SuperUserSignal.onMissing` |
| `DENIED` | su exists, privilege check failed | spawn succeeds, check script fails |
| `UNKNOWN` | not checked this session | — |

* `checkRootQuietly()` — no dialogs, single-flight; used by `BootWorker`.
* `forceGetRoot()` — interactive flow (splash/nav). `MISSING` shows a
  "Monitor mode" dialog instead of an endless retry loop.

### Transition (`runtime/NoRootMode.kt`)

On every detection (`BootWorker`, app start, mid-session `su` failure):

1. stop the ghost `ThermalService` (it cannot write anyway),
2. **auto-disable the engine** (`GLOBAL_SPF_PROFILE_OFF = true`) unless TRUE
   OFF already owns the state (rule 14) and set
   `GLOBAL_SPF_ENGINE_RESTORE_PENDING`,
3. persist honest evidence (`scene_boot`: reason, boot id, timestamp) — the
   boot is **never** marked "applied",
4. always post one notification with the real reason.

Recovery: with root back, Home shows a one-tap restore card
(`enableEngineFromRestore`) which clears the pending flag and re-runs
`ProfileController.setEngineEnabled(true)` + `ModeSwitcher.ensureReady()`.

### Gates (writers)

Every privileged entry point checks `CheckRootStatus.isAvailable()` and
refuses cleanly (log once, no partial writes):

* `ProfileController.applyInit/applyMode/release`
* `ModeSwitcher.initPowerCfg/ensureReady/applyBootState/executeMode/restoreSavedMode`
* `DaemonController.ensureOn/ensureOff`, `ThermalService.start/onCreate/loop`
* `HwuiController.applyActive/clear`
* `SepolicyOptimizer.apply`, `SepolicyCapability.probeAll`
* `StockSnapshot.ensureCaptured`
* `BatterySaverMode` (overlay never engages without root)
* `DndController` (never engages without root; restoring its own change stays allowed)

`BootWorker` short-circuits before `autoBoot()` when the quiet check is not
`AVAILABLE`. UI: Home banner (always when no root), Tuner switch locked with
the no-root note, profile editor read-only, Diagnostics accessible with a
"Root access" section.

## 2. Uninstall hygiene (app dihapus)

Persistent things that survive both a reboot *and* an uninstall:

| Artifact | Restored/removed by |
|---|---|
| `pm suspend/disable/hide` state | `PmStateJournal` → `SceneGuard` boot script / `SceneCleanup` |
| Tweaks `settings` keys | journaled as `setting\|ns:key` (deleted on restore) |
| `/data/adb/modules/scene_sepolicy` | removed by guard/cleanup |
| `/data/adb/modules/scene_systemless` (perf hooks) | removed by guard/cleanup |
| `/data/adb/modules/scene_resurgence` (rescue) | removed by guard/cleanup |
| `/data/local/tmp/scene_thermald.*`, `scene_policy.rules` | removed by guard/cleanup |
| `persist.vtools.suspend` | cleared by guard/cleanup |

* `PmStateJournal` (prefs `scene_journal`) records every PM/settings change.
  Every write mirrors the journal into `/data/adb/modules/scene_guard/journal.pm`.
* `SceneGuard` provisions `/data/adb/modules/scene_guard` on every rooted boot
  (`BootWorker`). Its `service.sh` exits immediately while
  `/data/data/com.omarea.vtools` exists; when the app is gone it replays the
  journal, deletes every Scene module + tmp artifact and removes itself.
* `SceneCleanup` (Settings ▸ *Leaving & uninstall cleanup*) performs the same
  sequence in-app: release engine → replay journal → remove modules/tmp →
  clear HWUI/journal. Run it before uninstalling (or rely on the guard).

Kernel tuning needs no cleanup: every node write is per-boot and the ROM's
`qcom-post-boot` restores stock values on reboot. If the app is deleted while
the engine is ON, the next boot simply never applies anything.

## Verification

* JVM: `NoRootSafetyTest` (classification, auto-off policy, journal script,
  guard script contract) + `NotificationFormatTest` (status/battery lines).
* Device: non-root → Home banner + notification, engine OFF, Tuner locked,
  editor read-only, Diagnostics "Root access"; status notification shows
  `Monitor mode · no root` and the right cell switches to `Status / Monitor`;
  `ThermalService` absent; no writes (`tools/rom-stock-check.sh`). With root →
  guard module present, engine applies normally, status notification shows
  battery state (`Charging/Discharging` + mA·W·%·°C) and offers the
  **Enable engine** action while the restore is pending; uninstall test:
  freeze an app, uninstall Scene, reboot → app unfrozen, modules gone.
