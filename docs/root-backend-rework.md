# Magisk / Root Backend Rework — Change Log

**Goal:** make the app not require Magisk, and strengthen the native root path so
systemless operations work with or without a module framework.

**Result:** `assembleDebug` ✅ · 114 unit tests ✅ (103 app + 11 common) · 0 failures

---

## Part 1 — Xposed and Zygisk

**Nothing to remove. Both were already fully gone.**

Verified by exhaustive grep across `app/src`, `common/src`, `krscript/src`:

| Search | Hits |
|---|---|
| `zygisk` (case-insensitive, all file types) | **0** |
| `xposed` / `Xposed` / `XPOSED` in real code | **0** |

The only matches were false positives on the word *"exposed"* in test method names
(`c3_cpu_available_frequencies_are_exposed`) and a comment in `MiuixCompat.kt`
("values are exposed through composition locals"). Nothing to delete.

The `magisk_replace.sh` comment "use Magisk if available, otherwise root replaces system
file directly" was the closest thing to a framework hook, and that mechanism is now
formalised (Part 3).

---

## Part 2 — The real problem: the root implementation was broken

`MagiskExtend.java` was written against **Magisk 17/18/19**. On a modern install:

| Issue | Line (old) | Impact |
|---|---|---|
| `magisk -V` to detect the version | 209 | **Removed from the Magisk CLI in v27+.** The call returns `error`, `supported` is set to `0`, and therefore `magiskSupported()` returns `false` **forever**. Every systemless feature in the app silently dies on any 2024+ install. This was the single most damaging defect. |
| `imgtool create /data/adb/magisk.img` | 77–113 | ~90 lines of dead code for Magisk <19 (2019). `imgtool` and `magisk.img` no longer exist. |
| `magisk_merge.img` / image resize | 79–93 | Same era. Unreachable on any current device. |
| `MAGISK_PATH_19` hardcoded | 115 | Ignored `$MAGISK_MODULE`, so it broke on non-standard install roots. |
| `setSystemProp` wrote `system.prop` only | 242–251 | Required a **full reboot** to apply. Density changes did not show until restart. |
| `magiskSupported()` gated everything | 64, 239 | **This was the Magisk dependency.** No Magisk → `createFileReplaceModule` returns `false` → feature dead. There was no fallback. |

So the class was simultaneously (a) broken on modern Magisk, (b) carrying 90 lines of
dead 2019 code, and (c) hard-requiring Magisk even on devices where a plain rooted
setup could have done the job.

---

## Part 3 — What changed

### `common/src/main/java/com/omarea/common/shared/MagiskExtend.java` (rewritten)

Replaced the Magisk-specific logic with a **runtime-selected backend facade**. Magisk is
now one option rather than a prerequisite.

```java
public enum Backend {
    NONE(0),        // no usable target
    OVERLAYFS(1),   // write into the root manager's module dir (Magisk/KernelSU/APatch)
    DIRECT(2)       // remount the partition and write in place
}
```

**Backend resolution** (`resolve()`, cached per process):

1. **OVERLAYFS** — tries `$MAGISK_MODULE` first (authoritative; set by Magisk, KernelSU
   and APatch alike), then `/data/adb/modules`, `/data/adb/modules_update`,
   `/sbin/.magisk/modules`, `/sbin/.core/img`. Preferred because it is non-destructive
   and survives an OTA.
2. **DIRECT** — probed by actually *writing* a test file after `mount -o rw,remount`,
   not by trusting `mount` output (dm-verity and shared-block layouts can report `rw`
   while still rejecting writes). **This is the path that removes the Magisk
   requirement entirely.**
3. **NONE** — neither works.

**Method-by-method changes:**

| Method | Change | Reason |
|---|---|---|
| `magiskSupported()` | No longer calls `magisk -V`; returns "a usable write backend exists" | The old probe is dead on Magisk 27+; this was the root cause of the silent feature loss |
| `moduleInstalled()` | Now overlay-specific only | Previously conflated "Magisk present" with "my module present", which is why callers could not tell the two modes apart |
| `isOverlayActive()` | **New** | Lets callers branch on the actual write strategy |
| `backend()` / `rootManager()` / `backendDescription()` | **New** | Diagnostics; fed to the log and the kr-script environment |
| `createFileReplaceModule()` | DIRECT branch added (was overlay-only) | Makes system-app conversion work with no module framework |
| `getReplacePath()` / `getMagiskReplaceFilePath()` | Returns the target unchanged on DIRECT | No overlay to redirect into |
| `deleteSystemPath()` | DIRECT branch restores from `.scene.bak` | The old code had no non-Magisk path at all |
| `setSystemProp()` | Now also applies **live** via `resetprop` (fallback `setprop`) | **Removes the mandatory reboot.** `resetprop` can also replace read-only props |
| `setBuildPropDirect()` | **New**; snapshots `.scene.bak` *before* editing | No overlay to persist into |
| `magiskModuleInstall()` | Idempotent, no-op on DIRECT | Removes the first-run install ceremony from the happy path |
| `diagnose()` / `resetCache()` | **New** | One-line backend summary for `scene-adb` and the test suite |
| `imgtool` / `magisk.img` code | **Deleted** (~90 lines) | Dead since Magisk 18 |
| `spaceValidation()` | **Deleted** | Only existed to size a loop image that no longer exists |
| `getTotalSizeOfFilesInDir()` | **Deleted** | Only called by `spaceValidation` |

`Backend` keeps the historical `0/1/2` integer codes (`Backend.NONE.code` etc.) so the
old public int constants did not silently change meaning.

### Shell layer

| File | Change | Reason |
|---|---|---|
| `kr-script/common/magisk.sh` | `apply_prop_live()` added; both prop setters call it. `|^$1=|` anchoring. `touch` before `sed` | Property changes took effect only after a reboot; unanchored `sed` could match a longer prop name; `sed -i` on a missing file no-opped silently |
| `kr-script/common/magisk_replace.sh` | **Bug fixed:** the direct-mode restore did `cp "$output.bak" "$output"` then `rm -f $output` — deleting the file it had just restored. Removed backend wording; added `sync` | Real data-loss bug in the restore path |
| `kr-script/common/props.sh` | Same live-apply change. **Bug fixed:** `status` leaked across sources, so a prop absent from `system.prop` could report a stale value from a previous check. `set_system_prop` no longer writes via `/cache/build.prop` and snapshots `.scene.bak` first | Correctness; also `/cache` is not guaranteed writable on modern Android |
| `kr-script/executor.sh` | Exports `ROOT_BACKEND` and `ROOT_MANAGER` | Scripts can branch without re-probing the device |

### Environment plumbing

`krscript/.../ScriptEnvironmen.java` now exports:

- `MAGISK_PATH` — overlay root, **empty** when no overlay backend (the existing signal
  scripts already test for)
- `ROOT_BACKEND` — `overlayfs` | `direct` | `none` *(new)*
- `ROOT_MANAGER` — `magisk` | `kernelsu` | `apatch` | `unknown` | `none` *(new)*

### Call sites

| File | Change | Reason |
|---|---|---|
| `ActivityMagisk.kt` | No longer finishes when Magisk is absent. Only scaffolds a module when `isOverlayActive()` | This was the hard "requires Magisk" gate |
| `ActivityMain.kt` | Install prompt now gated on `isOverlayActive()` | Users on a direct-root device were being nagged to install a module they did not need |
| `DialogAddinModifyDPI.kt` | The Magisk-vs-direct branch collapsed into `magiskSupported()`. The old inline `/data/build.prop` + `reboot` path removed | `setSystemProp` now handles both; the old path force-rebooted and left temp files behind |
| `DialogAppOptions.kt` | `moduleInstalled()` → `isOverlayActive()` | `deleteSystemPath` now supports DIRECT; gating on `moduleInstalled()` would have skipped it |
| `DialogSingleAppOptions.kt` | "requires Magisk 19.3+" wording and gating replaced with backend-neutral logic | Misleading message; the capability no longer needs Magisk |
| `strings.xml` | `magisk_install_desc` reworded (no longer names Magisk); added `root_backend_unavailable`, `density_applied` | User-facing accuracy |
| `AGENTS.md` | Zygisk added to the forbidden list; new **Root backend policy** section | Codifies the no-framework-required rule |

---

## Part 4 — kr-script page sweep

The 26 pages touching Magisk paths fell into three groups. Two needed no work at all.

| Group | Files | Status |
|---|---|---|
| A — call `magisk_replace.sh` helpers | `miui_hide_nav_get/set`, `msm_booster_get/set`, `msm_perfd_get/set` | **No change needed.** The helpers were fixed in Part 3, so these already work on either backend. |
| B — call `magisk_set_system_prop` | `hwui_render/set`, `hwui_vulkan/set`, `display/mainkey_set` | **No change needed.** Backend-aware after Part 3. |
| C — read `$MAGISK_PATH` directly, no fallback | 10 files | **Converted.** |

### The Group C problem

These wrote `$MAGISK_PATH$file` unconditionally and refused to run unless
`"$MAGISK_PATH" != ""`. With no Magisk they showed *"Add-on module not installed;
cannot apply changes."* — even though the same write is perfectly possible on the direct
backend. The `get`/`summary` sides also silently read the wrong file.

### Shared resolver added

New helpers in `kr-script/common/mount.sh` (already sourced by these pages):

| Helper | Purpose |
|---|---|
| `write_target_for <path>` | Returns the path to edit for **either** backend, or fails when neither is usable. Handles the `/vendor`+`/product` → `system/` promotion. |
| `write_backend_available` | True when an overlay **or** a direct write is possible. |
| `prepare_target_dir <target>` | Creates the parent dir, mounting first for real-partition targets. |
| `snapshot_target <target>` | One-time `.scene.bak`, direct mode only. |
| `restore_target <target>` | Restores from that snapshot. |

New page-visibility gate `kr-script/common/backend_available.sh`, replacing the old
`visible="if [[ $MAGISK_PATH != '' ]]..."` inline test.

### Files converted

| File | Change |
|---|---|
| `miui/ai_key/set.sh` | Routes through `write_target_for`; reset branch now drops the overlay copy *or* restores the snapshot |
| `miui/ai_key/get.sh` | Resolves the target, falls back to the live file |
| `miui/ai_key/summary.sh` | Same |
| `other/377_key/set.sh` | Same treatment as `ai_key/set.sh` |
| `other/377_key/get.sh` | Same as `ai_key/get.sh` |
| `other/377_key/summary.sh` | Same |
| `miui/miui_update_set.sh` | Both branches unified through the resolver. Dropped the `/cache/hosts` staging (not reliably writable). Anchored the `sed` pattern to `[[:space:]]\+` so it cannot match unrelated lines. |
| `miui/miui_update_get.sh` | Resolves the target, falls back to the live file |
| `developer/notch/apply.sh` | Routes through the resolver. **Fixed a doubled slash** (`${MAGISK_PATH}/system/...` where `MAGISK_PATH` already ends in `/`). |
| `display/display.xml` | `visible=` gate switched to `common/backend_available.sh` |

### Bugs fixed in this pass

1. **`img/twrp_install.sh` tested the misspelled `$magiakboot`** (line 36) — the variable is
   `magiskboot`, so the guard was *always* false and never fired. Its message was also
   inverted: it printed "Please install Magisk first" when Magisk **was** present.
   Now resolves `magiskboot` from `PATH` first, then the conventional Magisk/KernelSU/APatch
   locations, and prints an accurate message when genuinely absent.
   *(Note: this page legitimately requires `magiskboot` — it is the only tool that repacks
   Android boot images correctly. That is a tool dependency, not a framework dependency.)*
2. **`miui_update_set.sh` anchored its `sed` delete rule to an exact literal** with a hardcoded
   backslash-escaped spacing run, which is brittle across `hosts` layouts.
3. Two user-facing descriptions still said "Magisk" where the mechanism is now generic
   (`img/img.xml`, `more.xml`).

### Verification

- `bash -n` clean on all **12** modified scripts.
- All **10** kr-script XML files parse; `assembleDebug` succeeds; 114 unit tests pass.

### Still deliberately not changed

The remaining `$MAGISK_PATH` references in the converted files are **mode-detection**
(`"$target" == "$MAGISK_PATH"*` — "did I write to the overlay?"). That is correct and must
stay: the reboot wording depends on it. Overlay writes need a reboot; direct writes are live.

---

## Verification

```
./gradlew assembleDebug          → BUILD SUCCESSFUL
./gradlew testDebugUnitTest      → 103 tests, 0 failures
./gradlew :common:testDebugUnitTest → 11 tests, 0 failures
bash -n on all 5 modified .sh    → OK
```

New tests: `common/src/test/java/com/omarea/common/shared/MagiskExtendBackendTest.kt`
(11 tests) — pins the enum integer codes, the `system.prop` marker, the `vendor`/`product`
path-promotion rule, and that the exported `ROOT_BACKEND` strings match what scripts expect.

Regression grep for `xposed|vaddin|zygisk|magisk -V|imgtool|magisk.img|magisk_merge`
across live source returns **documentation only** — zero live references.

### Not verified

**On-device behaviour is unverified.** `adb devices` is empty; no device attached.
Backend resolution, the DIRECT write path, and live `resetprop` application all require
a real rooted device. Run `scripts/scene-adb all` once a device is connected.

---

## Still outstanding (deliberately not changed)

- **`CheckRootStatus` / `isMagisk()` helpers** still sniff for Magisk paths to *warn* about
  writability. That is a legitimate safety warning, not a dependency — the warnings are
  now backend-neutral in wording, but the detection remains Magisk-oriented.
- **`SwapModuleUtils.magiskModuleInstalled`** reads `/data/swap_config.conf` and the
  `vtools.swap.controller` prop. This is a *separate* swap-controller module, not the
  overlay backend, and was left alone.
- **26 kr-script pages** that reference `/data/adb/magisk` in inline shell were not
  rewritten in the first pass. **Resolved in Part 4** — see above. The only remaining
  `magiskboot` reference is a legitimate toolchain requirement, resolved from `PATH` plus
  the conventional Magisk/KernelSU/APatch locations.

---

# Part 5 — Full de-Magisk pass (final)

Directive: *"hapus full magisk nya termasuk fitur yg bergantung pada magisk tapi kalau kamu
bisa mengakali boleh banget"* — remove Magisk completely, including features that depend on
it, but workarounds are welcome.

## 5.1 Removed outright

| Item | Why |
|---|---|
| `ActivityMagisk.kt` | The whole "Extra Module" page existed only to browse Magisk modules. User chose *delete the whole page*. |
| `FragmentMagisk{AfterStart,BeforeStart,Files,Props}.kt` | The page's four fragments. |
| `activity_magisk.xml`, `fragment_magisk_*.xml` (×4) | Layouts for the above. |
| `drawable-v21/app_options_magisk.png` | Artwork only used by that page and one dead layout. |
| `layout/dialog_swap_module.xml` | Nothing inflated it — `swapModuleUpdateDialog()` was its only consumer and both are gone. |
| `menu_app_magisk` string, `nav_app_magisk` id, nav entry in `FragmentNav.rootRequiredIds` | Orphaned by the page deletion. |
| `swap_module_target_version` / `swap_module_download_url` (`configs.xml`) | Pointed at the third-party `swap-controller-3.2.4.zip` download. |
| `swap_module_install_desc` (strings) | Text read *"After downloading the module, please brush in Magisk Manager by yourself"* — Magisk-branded and structurally wrong now that Scene owns the config. |
| `swap_outside_module`, `swap_outside_module_desc`, `swap_outside_effect{1,2,3}` | The "external additional (Magisk) module" marketing copy. |
| `MagiskExtend.java`, `MagiskExtendBackendTest.kt` | Superseded by `RootBackend` / `RootBackendTest`. |
| `magisk.sh`, `magisk_replace.sh` (and the compatibility shim) | Renamed to `overlay.sh` / `overlay_replace.sh`; all six `source` sites updated rather than aliased. |
| `isMagisk()` in `DialogAppOptions` / `DialogSingleAppOptions` | Decided *nothing*. It cached `su -v | contains "MAGISKSU"`; on KernelSU/APatch it returned `false` and the overlay side-effect warning silently never fired. Every call site already had a better backend-neutral guard (`RootBackend.overlayReady()`, `RootBackend.supported()`). |
| `useMagisk` local, `moveToSystemMagisk()`, `useMagisk=true` | Renamed to `useOverlay` / `moveToSystemOverlay`; these route through `RootBackend` and were never Magisk-specific. |

## 5.2 Kept and de-branded

| Item | Change |
|---|---|
| `SwapModuleUtils` | No longer looks for a module. Reads `/data/adb/scene/swap.conf`, which Scene writes itself via `SwapUtils`. Gate renamed `magiskModuleInstalled` → `configReady`; `getModuleVersion()` deleted; `saveModuleConfig` now creates the dir and appends missing keys so `sed` always has a line to match. |
| `activity_swap.xml` card | Retitled to "Boot persistence" with a single "Active" indicator. The three-way enabled/disabled/update row and the green "Update/Download available" label were removed. |
| `BootWorker` | Swap re-application no longer short-circuits on `getprop vtools.swap.controller == magisk` — that prop was set by the third-party module, so the check suppressed exactly the users who needed it least. |
| `props.sh`, `backend_available.sh`, `mount.sh`, `overlay.sh`, `overlay_replace.sh` | `$MAGISK_PATH` → `root_overlay_dir()` / `$OVERLAY_PATH`. `MAGISK_PATH` survives only as a documented legacy alias for scripts (and root managers) that still export it. |
| `dialog_app_trans_mode.xml` | "Create Magisk module" → "Systemless overlay"; icon switched to `app_options_as_system`. |
| `magisk_install_{title,desc}` | Rebranded to "Scene overlay". |
| `FragmentCpuModes.getOnlineConfig()` | "flash it with Magisk" → "flash it with your root manager". |
| `error_su_timeout` | "SuperSu, Magisk Manager" → "Magisk, KernelSU, APatch". |
| Instrumented test `i1_magisk_modules_path_detected_or_absent` | Renamed to `i1_overlay_modules_path_detected_or_absent`; feature id `root.magisk_modules_path` → `root.overlay_modules_path`. |

## 5.3 Workarounds applied

- **Swap survives without its module.** The module only ever provided `/data/swap_config.conf`
  plus a `vtools.swap.controller` prop. Scene already performs the swap itself
  (`mkswap` / `swapon` / `swapoff` in `SwapUtils`) and re-applies it on boot (`BootWorker`),
  so the feature was already self-sufficient — the module was a duplicate, not a dependency.
  It now reads and writes its own config under `/data/adb/scene/`.
- **Reboot-free property changes.** `apply_prop_live()` prefers `resetprop` so a prop change
  takes effect immediately instead of waiting for a boot. It can also replace read-only
  props, which `setprop` cannot. Falls back to `setprop` when `resetprop` is absent.
- **Overlay still reachable on every root manager.** `overlayCandidates()` probes
  `$MAGISK_MODULE`, then `/data/adb/scene/overlay`, then `/data/adb/modules`,
  then `/data/adb/modules_update`, and finally decides by *writing a probe file* rather
  than trusting `mount` output.

## 5.4 Deliberately still present

Three references remain on purpose. None is a runtime dependency.

1. **`$MAGISK_MODULE`** in `RootBackend.overlayCandidates()` — an environment variable set
   by several root managers. Reading it is interop, not a requirement.
2. **`magiskboot`** in `img/twrp_install.sh` — the upstream binary every boot-image tool
   chain ships under that name. It is resolved from `PATH` first, then the conventional
   Magisk / KernelSU / APatch directories. Renaming it locally would break real devices.
3. **`app/src/main/assets/powercfg/*/powercfg-base.sh`** comments referencing
   `[Magisk]SwapController` — historical comments in vendored third-party tuning profiles
   that are not part of Scene's own code.

`com.topjohnwu.magisk` also stays in two app-list arrays (`configs.xml`) — these are
*freeze-list suggestions*, i.e. names of apps a user might want to freeze, not imports.

## 5.5 Residual cleanup (second sweep)

After the first sweep, a stricter pass found four more places where Magisk was still
welded into behaviour rather than merely mentioned.

| Item | Change |
|---|---|
| `addin/power_save_set.sh`, `aosp/ps/set.sh` | The daemon-kill step hardcoded `magisklogd`. Replaced with a loop over `magiskd magisklogd ksud apd` so the step works on whichever root manager is actually installed, and silently skips the ones that are absent. |
| `hwui_render/set.sh`, `hwui_vulkan/set.sh`, `display/mainkey_set.sh` | User-facing toast said *"Changed $prop via Magisk"*. Now *"via the overlay"* — the operation goes through `root_overlay_dir()`, which is not Magisk-specific. |
| `other/resurgence/set.sh` | *"> Disable all Magisk modules except itself"* → *"> Disable all other overlay modules except itself"*. |
| `props.sh` reader path | `cat_prop_is_1` read `$MAGISK_PATH/system.prop` directly. Now goes through `root_overlay_dir()`, so it resolves correctly in DIRECT mode too (where `MAGISK_PATH` is empty). That was a real functional gap, not just branding. |

## 5.6 Harness alignment

The ADB harness still described the root backend in Magisk terms, which made the
`doctor` output misleading now that no module framework is involved.

| Item | Change |
|---|---|
| `scripts/scene-adb` `doctor` probe | The `/data/adb/modules` check was labelled `magisk` and exported as `magiskModules`. That directory is an *overlay candidate*, not a Magisk-specific path, so it is now `overlay` / `overlayModules` — matching the renamed `root.overlay_modules_path` feature id in the instrumented suite. |
| `scripts/scene-adb` inline comment | Referenced `MagiskExtend.resolve()`, a class deleted in this pass. Now `RootBackend.resolve()`, with a note that no module framework is required either way. |
| `scripts/scene-adb` `doctor` exit code | Always returned a generic `1` on failure, contradicting its own documented table. It now returns `2` (adb missing), `3` (no device) or `4` (device outside scope) for the first blocking problem, so an agent can branch on *why*. |

Verified: `bash -n` clean; `doctor` returns `3` with no device attached and `2`
with adb hidden; `doctor --json` emits the renamed field; the parser self-test
is still 10/10.

## 5.7 Verification

```
bash -n  on all kr-script *.sh        → OK
xml parse on all kr-script *.xml      → OK
./gradlew assembleDebug               → BUILD SUCCESSFUL
./gradlew testDebugUnitTest           → pass
./gradlew :common:testDebugUnitTest   → pass
```

Regression grep `magisk` across live `.kt`/`.java`/`.xml` now returns **only**: the
three deliberate cases above, `RootBackend`'s own Javadoc provenance note, and
`RootBackendTest` asserting the state dir contains no framework name.

### Not verified

On-device behaviour remains unverified — `adb devices` is empty. Backend resolution,
the DIRECT write path, and live `replace-prop` application all need a real rooted
Xiaomi/Qualcomm device. Run `scripts/scene-adb all` once one is connected.

---

# Part 6 — Residual sweep (completion)

Part 5 renamed the files and the Java class but left the shell function names, an unused
string pair and stale AGENTS.md policy text. This pass finishes them.

## 6.1 kr-script helper functions renamed

`overlay.sh` / `overlay_replace.sh` kept their `magisk_*` function names behind a NOTE
claiming page scripts still called them. The call sites have now been updated, so the names
are neutral too. The inverted return convention (1 = applied) is preserved everywhere, so
behaviour is unchanged.

| Old | New | Call sites updated |
|---|---|---|
| `magisk_replace_file` | `overlay_file_replace` | `overlay_replace.sh` |
| `magisk_cancel_replace` | `overlay_file_restore` | `overlay_replace.sh` |
| `magisk_file_exist` | `overlay_file_exists` | `overlay_replace.sh` |
| `magisk_file_equals` | `overlay_file_matches` | `overlay.sh`, `overlay_replace.sh` |
| `magisk_set_system_prop` (overlay.sh alias) | **deleted** — `set_system_prop_override` is the single name | `mainkey_set.sh`, `hwui_render/set.sh`, `hwui_vulkan/set.sh` |
| `magisk_cancel_system_prop` | `cancel_system_prop_override` | none (was uncalled) |
| `module_installed` | `overlay_available` | `overlay_replace.sh` |
| `overlay_active` (alias) | **deleted** (uncalled) | — |

`props.sh` carried a second, duplicate `magisk_set_system_prop` definition; it is renamed to
`set_system_prop_override` to match `overlay.sh`. Its callers (`hwui_*`, `mainkey_set`) source
`props.sh`, not `overlay.sh`, so both definitions remain — same behaviour, same env inputs.

## 6.2 Dead strings and user-facing wording

- `magisk_install_title` / `magisk_install_desc` had **0 references** in Java, layouts and
  scripts — removed. `root_backend_unavailable` carries the live message.
- `developer/ab_updater.sh` warned "do not install Magisk before rebooting" → now says
  "do not install a root manager".

## 6.3 Policy docs realigned

`AGENTS.md` still described a `MagiskExtend` facade with `magiskSupported()` /
`moduleInstalled()` / `resolve()` — none of which exist any more. The policy now names the
shipped API and the script contract:

- `RootBackend.supported()` (any backend) · `RootBackend.overlayReady()` /
  `RootBackend.isOverlayActive()` (overlay only) · `RootBackend.backend()`,
  `diagnose()`, `getOverlayPath()`.
- Scripts receive `OVERLAY_PATH` plus the `MAGISK_PATH` legacy alias, `ROOT_BACKEND`
  (`overlay|direct|none`), `ROOT_MANAGER`; helpers live in `common/mount.sh` and
  `common/overlay.sh`.
- The verify-checklist numbering (`3.` after `5.`) was fixed to `6.`.

## 6.4 Deliberately still present

Unchanged from §5.4, and now the only `magisk` hits in live code:

| Item | Rationale |
|---|---|
| `$MAGISK_PATH` (env alias in `executor.sh`, `mount.sh`, `overlay.sh`) | compatibility for page scripts / online addins that still read it; `OVERLAY_PATH` is authoritative |
| `magiskboot` in `img/twrp_install.sh` | the upstream boot-image tool name; resolved from `PATH`, then the conventional Magisk/KernelSU/APatch dirs |
| `magiskd magisklogd ksud apd` daemon list in `power_save_set.sh`, `aosp/ps/set.sh` | kills whichever root daemon is actually running |
| `com.topjohnwu.magisk` in `configs.xml` + `ActivityFreezeApps.kt` | freeze-list suggestion — the manager app a user may want suspended |
| vendored `powercfg/*/powercfg-base.sh` comments | third-party tuning profiles, not Scene code |
| `SwapModuleUtils` history comment, `RootBackend` Javadoc provenance | historical record, not runtime |

## 6.5 Verification

```
bash -n on the 7 modified kr-script files   → OK
./gradlew assembleDebug                     → BUILD SUCCESSFUL
./gradlew testDebugUnitTest                 → 103 tests, 0 failures
./gradlew :common:testDebugUnitTest         → 11 tests, 0 failures
grep magisk_* function names in assets      → 0
grep xposed|vaddin|zygisk|imgtool|magisk.img|magisk_merge|magisk -V in live code → 0
```

### Not verified

Same standing caveat as Part 5: on-device execution needs a connected device. The renamed
helpers are pure renames with identical return conventions and call paths, so the on-device
test matrix from §5.7 is unchanged.
