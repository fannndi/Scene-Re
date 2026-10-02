# Attribution

Scene-Re is licensed **GPL-3.0**. Some tuning content, state-machine patterns
and data were adopted (with gratitude) from other open-source projects. This
file records them.

## Encore Tweaks — Rem01Gaming (Apache-2.0)

Source: <https://github.com/Rem01Gaming/encore> · License: Apache-2.0
(copy in [`third_party/Apache-2.0-encore.txt`](third_party/Apache-2.0-encore.txt)).

Adopted into Scene-Re:

| Area | What | Where |
|---|---|---|
| Network pack | TCP congestion **preference list** + `tcp_fastopen`, `tcp_low_latency` | `tuning.json` `init.net`, `ProfilePlanner.netOps` |
| Kernel sysctls | `sched_nr_migrate`, `sched_child_runs_first`, `sched_autogroup_enabled`, `perf_cpu_time_max_percent`, `sched_schedstats` | `tuning.json` `init.kernel` |
| VM/block | `vm.stat_interval`, `queue/iostats`, `queue/add_random` | `tuning.json` `init.vm` / `init.io` |
| Game libraries | `sched_lib_name` list (Unity/IL2CPP/UE4/Godot…) + `sched_lib_mask_force` | `tuning.json` `init.sched_lib` |
| Bus/DDR | devfreq latency-domain strategy (`max`/`mid`/`min`/`unlock`, `which_midfreq` mid-OPP rule) | `ProfilePlanner.devfreqOps`, `DeviceCaps.midFreq` |
| KGSL | `bus_split` / `force_clk_on` performance pair | `tuning.json` profiles `gpu` |
| Battery saver | saver ON → powersave, saver OFF → previous mode | `runtime/BatterySaverMode` |
| DND | silence interruptions for a game session, restore previous filter | `runtime/DndController` |
| Games list | `gamelist.txt` (~530 package names) | `app/src/main/assets/games/encore_gamelist.txt` |
| Diagnostics | pstore + device header bugreport pattern | `DiagnosticsCollector` |
| Device rules | mitigation concept (`NO_PERFORMANCE_GOV`, `NO_KGSL_FORCE_CLK`, `DISABLE_DDR_TWEAK`, `QCOM_NO_GPU_POWERSAVE`) | `tuning.json` `mitigations`, `ProfilePlanner` |

Values are **not** copied blindly: every node was probed on the target device
first, absent features stay locked/reported, and Scene-specific safety rules
(no charging writes, no thermal fighting, snapshot restore) still apply.

## AZenith — Zexshia (Apache-2.0)

Source: <https://github.com/Liliya2727/AZenith> · License: Apache-2.0
(copy in [`third_party/Apache-2.0-azenith.txt`](third_party/Apache-2.0-azenith.txt)).
AZenith itself is built on Encore Tweaks (also Apache-2.0, credited above).

Adopted into Scene-Re:

| Area | What | Where |
|---|---|---|
| Devfreq governors | driver-native `governor` mode (string / suffix-pattern map) beside the min/max pinning | `ProfilePlanner.devfreqOps`, `tuning.json` `devfreq.governor` |
| Workqueue | `workqueue.power_efficient` per profile (N latency / Y power) | `tuning.json`, `ProfilePlanner` |
| Kernel sysctls | `sched_migration_cost_ns` added to the kernel pack | `tuning.json` `init.kernel` |
| Game priority | `renice -20` + realtime I/O priority for apps that own a mode | `runtime/ProcessPriority` |
| Game preload | bounded page-cache read of a game's native libs on launch (no vmtouch binary shipped) | `runtime/GamePreload` |
| Refresh rate | persist the per-app SF mode, auto-apply on switch, restore on leave | `runtime/RefreshRateController` |
| Resolution downscale | per-app `cmd game downscale`, journal-mirrored for uninstall | `runtime/DownscaleController` |
| Bypass charging | opt-in threshold charge pause with full hygiene (the rule-12 exception) | `runtime/BypassCharging` |
| Maintenance tools | JIT `speed-profile` compile + `sm fstrim` | `runtime/SystemTools` |
| Backup/restore | full config backup file + one-tap restore | `runtime/ConfigBackup` |

Not adopted (with reasons): wholesale `cooling_device cur_state` thermal PID
(Scene's guard only lowers `scaling_max`; the kernel keeps its safety net),
thermal-engine disable / `logd` kill / tracing disable (invasive, no evidence),
SurfaceFlinger phase-offset props (needs measurement first), the root
`app_process` foreground monitor (a11y already works; large architecture
change), per-game 30 s focus-hold (conflicts with Scene's per-app intent
model; the existing dynamic-control delay covers flapping).
