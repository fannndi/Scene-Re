# IRQ affinity (opt-in, experimental)

Pins the Qualcomm GPU (`kgsl-3d0`) and display (`msm_drm`) IRQs to chosen
CPUs so they stop sharing one core. Source idea: **IRQ-Balancer-Configuration**
(Magisk module) — adopted with two corrections found by device experiment.

Status: **opt-in, default OFF** (`Other settings → IRQ affinity`), engine-ON
only, fully restored on engine OFF / TRUE OFF / cleanup / uninstall.

## Why the conf needs a rewrite at all

`msm_irqbalance` (init service `vendor.msm_irqbalance`) rebalances managed
IRQs within ~90 s, so a plain `smp_affinity_list` write is overridden:

| Experiment | Write | After ~90 s |
|---|---|---|
| plain write, stock conf | kgsl=6, msm_drm=7 | **1 / 0** (daemon moved them) |
| plain write, stock conf | kgsl=3, msm_drm=4 | moved again |
| patched conf + restart | kgsl=6, msm_drm=7 | **6 / 7 → sticky** (150 s+) |
| patched conf + restart | kgsl=3, msm_drm=4 | **3 / 4 → sticky** (90 s+) |

Sticky runs also showed real interrupt counts landing on the pinned CPUs
(e.g. +1604 on CPU7 for msm_drm), i.e. the pin is effective, not cosmetic.

## The hwirq/virq trap (module bug)

`/proc/interrupts` on this ROM:

```
127: ...  PDC-GIC 115 Edge      msm_drm
383: ...  PDC-GIC 332 Level     kgsl-3d0
```

* `127` / `383` are the **Linux virqs** — what `/proc/irq/<n>/smp_affinity_list`
  accepts.
* `115` / `332` are the **hwirqs** — and `msm_irqbalance`'s `IGNORED_IRQ` list
  expects **hwirqs**.

The IRQ-Balancer module writes the virqs into `IGNORED_IRQ`; the daemon logs
`WARNING: Cannot find matching virq for hwirq(127).` and keeps managing the
IRQ, so the module's pinning is silently ineffective. Scene writes the hwirqs
and the daemon really ignores the IRQs:

```
MSM-irqbalance: Ignored IRQs: 4 8 6 383 127
```

(mapped back to virqs by the daemon; `4 8 6` are the stock timer IRQs).

## How Scene applies it

Values live in `tuning.json` top level (not per profile yet):

```json
"irq_affinity": { "kgsl": "6", "msm_drm": "7" }
```

`runtime/IrqAffinity` (opt-in pref `irq_affinity_enabled`):

1. discover virq+hwirq by name from `/proc/interrupts` (`IrqAffinityPolicy`),
2. if `msm_irqbalance` runs and the conf is not already bind-mounted:
   patch `IGNORED_IRQ` with the hwirqs, write
   `/data/local/tmp/scene_irqbalance.conf`, `mount --bind` it over
   `/vendor/etc/msm_irqbalance.conf` (`/vendor` is ext4 **ro**; the bind mount
   is the only way) and `restorecon -F` it,
3. restart the daemon (`setprop ctl.restart vendor.msm_irqbalance`, `kill`
   fallback — init restarts it either way),
4. write `smp_affinity_list` for both virqs and `renice -10` the daemon.

The whole script runs in the **global mount namespace** via
`nsenter -t 1 -m -- sh …` (`su -M` fallback). This is essential: APatch's `su`
gives app sessions a **private mount namespace**, so a bind mount created from
the app's root shell is invisible to init — the restarted daemon read the
stock conf while the app believed it was mounted (found during device
verification).

Restore: `umount` + remove the tmp conf + restart the daemon (it manages the
IRQs again). The per-boot `StockSnapshot` also captures both
`smp_affinity_list` values dynamically, so engine OFF writes back the
pre-engine values.

## Notes / limitations

* Verified on POCO X3 NFC (sm6150, MIUI 12): virqs 383/127, hwirqs 332/115.
  Discovery is by name, so other Qualcomm devices with the same daemon work.
* `mount --bind` + `restorecon` relabels the source file to
  `vendor_configs_file`; removing it later still works from the root shell.
* No daemon (`pidof msm_irqbalance` empty) → the conf steps are skipped and
  the affinity writes are applied directly (nothing overrides them).
* The values `6`/`7` are the module's defaults; benchmarks (profile-vs-stock)
  still need to tell whether gold cores are the best target on surya.
  Per-profile values are a follow-up.
