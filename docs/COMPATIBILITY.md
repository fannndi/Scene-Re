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
  | `usb_pd` | `pd_allowed` switch missing (kernel exposes `pd_active` only); `BatteryUtils.setAllowed` now guards it |
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
