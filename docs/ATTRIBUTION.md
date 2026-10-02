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
| I/O scheduler | per-profile UFS scheduler (noop/cfq) + nr_requests + vfs_cache_pressure | `tuning.json`, `ProfilePlanner`, `DeviceCaps` |
| Thermal signal | EWMA smoothing, sensor-anomaly rejection, clamp-episode effectiveness, predictive pre-clamp | `ThermalController`, `ThermalService` |
| Refresh props | persist.vendor/sys.display.refresh_rate sync | `RefreshRateController` |
| Per-game extras | per-app Boost/Preload overrides + display-restart option | `runtime/GameExtras`, `DisplayRestart` |
| Logging | opt-in statsd/traced/charge_logger stop with full restore | `runtime/LoggingReduction` |
| Crash guard | opt-in panic=0/panic_on_oops=0 (JSON-driven values) | `runtime/KernelCrashGuard` |
| Frame pacing | opt-in `debug.sf.*` phase offsets/durations (pure math) | `runtime/SfFramePacing` |
| Foreground fallback | a11y-free root shell watcher + broadcast pipeline (shell edition of AppMonitor; no hidden-API dependency) | `runtime/RootForegroundWatch`, `ForegroundFallback` |

Not adopted (with reasons): wholesale `cooling_device cur_state` thermal PID
(Scene's guard only lowers `scaling_max`; the kernel keeps its safety net —
thermalcore's brain was adopted via EWMA/anomaly/pre-clamp/effectiveness),
thermal-engine disable / `logd` kill / tracing disable (invasive, no evidence;
only statsd/traced/charge_logger are optionally stopped, logcat stays),
`step_wise` on all 82 thermal zones (the majority already run it; forcing the
3 `user_space` zones would fight the thermal framework), schedtune boost on
all groups (Scene targets top-app only), EAS disable / FPSGO / GED / Mali /
PPM (not present or not applicable on this Qualcomm device), the Kotlin
`app_process` + hidden-API monitor (replaced with the safe shell watcher),
and per-game 30 s focus-hold (conflicts with Scene's per-app intent model;
the existing dynamic-control delay covers flapping).

## SkiaShift — Jefino9488 (no license file)

Source: <https://github.com/Jefino9488/SkiaShift> · the repository ships no
LICENSE file, so **no code was copied** — only the public prop names and the
Vulkan/GL decision table were re-implemented from scratch.

Adopted into Scene-Re:

| Area | What | Where |
|---|---|---|
| HWUI companion props | `debug.hwui.use_buffer_age` + `renderthread.skia.reduceopstasksplitting` follow the effective backend (set with a Vulkan renderer, removed otherwise) | `HwuiController.applyScript`, `HwuiResolution.isVulkanEffective` |

Verified before adoption: both props exist in this ROM's Android 12 `libhwui.so`
(`debug.hwui.skia_use_perf_hint` and `debug.renderengine.backend` are **not**
present and were skipped). SkiaShift's per-app approach is LSPosed/native-hook
based; Scene keeps its prop-based per-app override (single HWUI writer, hard
rule 2) instead.
