# Kernel / ROM compatibility layer

Scene must run on several surya kernels (stock MIUI, community builds like
`farewell-ext_surya`, future builds) and match the running ROM. A tuning key
that the kernel does not provide must be **locked and reported**, never
silently half-applied.

## How it works

1. **Registry** — `engine/KernelCompat.kt` declares every feature the app
   relies on: `id`, label, axis (`KERNEL`/`ROM`), probe path(s) and a port
   hint for kernel patches. `#nonempty` probes require file content (e.g.
   UFS health descriptor).
2. **Probe** — one root-shell round trip (`probeScript()`) checks every probe
   (`[ -e ]` / `[ -s ]`) and decodes `CONFIG_*` flags from `/proc/config.gz`
   when present. Cached in `scene_compat` prefs; refreshed on boot
   (`BootWorker`) and on every diagnostics export.
3. **Locking**
   - **Execution**: `ProfileApplier`'s `set_value` helper echoes
     `SCENE_MISSING:<node>` for missing nodes; the applier logs
     "locked N op(s)" — so data-driven keys (`tuning.json`) become inert but
     visible.
   - **Catalog**: `Parameter.sh` marks locked paths
     `** LOCKED (<feature> not in kernel) **`.
   - **UI**: Tweaks ▸ Kernel lists locked features; Diagnostics has a
     "Kernel compatibility" section (kernel build, feature counts, lock
     reasons, config flags).
4. **Kernel patches** — features that exist in stock MIUI/community kernels
   but not in the vanilla tree are listed in `docs/KERNEL.md`
   ("Port wishlist") with their exact node interfaces.

## Current status (device, 2026-09-30)

- Kernel: `4.14.180-perf` (community build, `farewell-ext_surya` lineage).
- Available: all core tuning features (core_ctl, WALT, cpu_boost, msm_perf,
  schedutil, kgsl knobs, cpuset incl. MIUI extras, thermal_message, LMK,
  charging, UFS knobs).
- Locked on this kernel:
  | id | reason |
  |---|---|
  | `vm_page_cluster` | legacy sysctl, removed from modern kernels (no patch needed) |
  | `bus_dcvs` | driver not in this kernel; real bus nodes are `devfreq/soc:qcom,cpu-*-bw`, `memlat` |
  | `ddr_fixed` | `/dev/scene/*` nodes not provided by this kernel |
  | `fpsgo` | `/sys/kernel/fpsgo` missing (FPS falls back to `measured_fps`) |
  | `perfmgr` | MIUI perfmgr module not in this kernel |
  | `migt_glk` | migt exists (`CONFIG_MIGT=y`) but exposes `migt_freq` etc., not `glk_maxfreq` |
  | `ufs_health` | health-descriptor support removed by the kernel patch (files exist but empty) |
  | `usb_pd` | `pd_allowed` switch missing (kernel exposes `pd_active` only); PD state is read-only by policy |
  | `msm_thermal` | no `msm_thermal` module (userspace thermal goes through `thermal_message`) |
  | `uclamp` | `sched_util_clamp_*` sysctls absent (util-clamp not built/exported) |
  | `adrenoboost` | `devfreq/adrenoboost` attr not exposed (plain msm-adreno-tz only) |
- Kernel-side drift (community build has, vanilla tree lacks): see
  `docs/KERNEL.md` port wishlist — `sched_boost_top_app`,
  `cpu_boost/sched_prefer_idle`.

## Policy for new tunables (agents)

1. Add the key to `tuning.json` + `ProfilePlanner` **and** register the node
   family in `KernelCompat` (probes + nodes + hint).
2. Never trust "node exists" at write time only — the applier report and the
   catalog marker are the required UX.
3. Kernel features missing from the vanilla tree get a `hint` so they appear
   in diagnostics and the KERNEL.md wishlist.

## SELinux direct-write capability (device-verified, 2026-10-01)

The `scene_sepolicy` APatch module grants untrusted_app access to the tuning
node families. What actually enforces on this device/build:

| Class | Verdict |
|---|---|
| Reads (`vendor_sysfs_*`, `sysfs_thermal`, `sysfs_zram`, `proc_swaps`, …) | **enforce** — battery/zram/thermal/scsi reads run direct, zero avc spam |
| Writes (all families) | **do not enforce** — see below |

Write findings (multiple controlled reboots, controlled payloads):

- Write statements appear in the policy blob (`magiskpolicy --print-rules`
  merges them: `{ read write … }`), while the *read* perms from the same
  statements enforce — so the module is loaded and parsed.
- Actual app writes still fail with plain `EACCES` on **0666** nodes with
  **no avc audit line**, through every open mode (truncate / append / rw).
  Checked with fresh tuples (types the ROM has no untrusted_app rule for),
  combined-perm statements and ordering variants.
- A silent EACCES at 0666 is neither DAC nor SELinux — it is a write guard
  below/above SELinux (kernel/APatch layer), out of a sepolicy module's
  reach. The root shell (magisk domain) is therefore the **supported write
  path** on this device; ProfileApplier's per-op fallback provides it.
- `Proc` writes are doubly blocked: procfs sysctls refuse chmod (0644 stays
  0644), so DAC alone rules them out — no `proc` write rule is shipped.

Consequences shipped:

- `engine/SepolicyCapability` probes every family by rewriting its current
  value; results land in `Diagnostics ▸ SELinux capabilities`, a Tweaks
  action and `files/debug/sepolicy-caps.txt` (pullable). The in-memory cache
  makes ProfileApplier skip futile direct attempts after the first boot probe
  (no failed opens, fewer avc lines, apply stays fast).
- Write rules stay in the module (portability to builds where they enforce)
  and stay behind the opt-in direct-writes toggle; the generic `sysfs file
  { write }` grant is documented as broad and is a no-op on this device.
- LMK (`lowmemorykiller/minfree`) joins the tuning schema as `lmk.minfree`
  (six ascending page counts), applied per profile through the fallback path
  — verified on device (performance relaxes, powersave tightens).
