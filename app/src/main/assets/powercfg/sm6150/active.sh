#!/system/bin/sh

action=$1
task=$2

cfg_dir=$(cd $(dirname $0); pwd)

if [[ ! -f "$cfg_dir/powercfg-utils.sh" ]]; then
  echo "The dependent '$cfg_dir/powercfg-utils.sh' was not found !" > /cache/powercfg.sh.log
  exit 1
fi

source "$cfg_dir/powercfg-utils.sh"

init () {
  if [[ -f "$cfg_dir/powercfg-base.sh" ]]; then
    source "$cfg_dir/powercfg-base.sh"
  elif [[ -f '/data/powercfg-base.sh' ]]; then
    source /data/powercfg-base.sh
  fi
}

if [[ "$action" == "init" ]]; then
  init
  exit 0
fi

# Off: restore the boot-stock kernel/system state and touch nothing else.
if [[ "$action" == "off" ]]; then
  restore_boot_stock
  exit 0
fi

if [[ "$action" == "fast" || "$action" == "pedestal" ]]; then
  devfreq_performance
else
  devfreq_restore
fi
reset_basic_governor

if [[ "$action" = "powersave" ]]; then
  # Endurance profile: a full day away from a charger. The caps stay low, the
  # little cluster keeps a slow ramp-up and the big cluster an even slower one,
  # input boost stays off and the big cores are allowed to power collapse.
  set_cpu_governor_scenario 0 powersave
  set_cpu_governor_scenario 6 powersave
  set_gpu_governor_scenario gpu
  set_cpu_freq 5000 1612800 5000 1555200
  set_input_boost_freq 0 0 0
  set_hispeed_freq 1248000 806400
  sched_boost 0
  stune_top_app 0 0
  # Endurance: enable core control on the little cluster (MIUI targetconfig:
  # CoreCtlCpu=0, MinCoreOnline=0) so idle cores can power collapse.
  cpu0_core_ctl on
  cpu6_core_ctl on
  sched_config 75 92
  sched_limit 1000 1000 2000 2000
  cpuset '0-1' '0-3' '0-3' '0-7'
  ufshc_perf off

elif [[ "$action" = "balance" ]]; then
  # Daily profile for social media / communication: a short little-cluster
  # input boost keeps tapping and scrolling smooth without waking the big
  # cluster, everything else stays frugal.
  set_cpu_governor_scenario 0 balance
  set_cpu_governor_scenario 6 balance
  set_gpu_governor_scenario gpu
  set_cpu_freq 5000 1708800 5000 1843200
  set_input_boost_freq 1488000 0 80
  set_hispeed_freq 1248000 1209600
  sched_boost 0
  stune_top_app 0 0
  cpu0_core_ctl off
  cpu6_core_ctl off
  sched_config 68 82
  sched_limit 0 0 0 0
  cpuset '0-1' '0-3' '0-5' '0-7'
  ufshc_perf off

elif [[ "$action" = "light" ]]; then
  # Light games: no performance scheduler, no input boost and no UFS boost.
  # The optional light-game frequency caps are applied by the options layer on
  # top of this profile.
  set_cpu_governor_scenario 0 light
  set_cpu_governor_scenario 6 light
  set_gpu_governor_scenario gpu
  set_cpu_freq 5000 1708800 5000 1843200
  set_input_boost_freq 0 0 0
  set_hispeed_freq 1248000 1209600
  sched_boost 0
  stune_top_app 1 0
  cpu0_core_ctl off
  cpu6_core_ctl off
  sched_config 72 86
  sched_limit 3000 1500 0 0
  cpuset '0-1' '0-3' '0-5' '0-7'
  ufshc_perf off

elif [[ "$action" = "performance" ]]; then
  # Gaming: ondemand's fast ramp keeps frames steady without pinning the
  # clusters at their maximum (the caps below bound the heat).
  set_cpu_governor_scenario 0 performance
  set_cpu_governor_scenario 6 performance
  set_gpu_governor_scenario gpu
  set_cpu_freq 300000 1804800 300000 2304000
  set_input_boost_freq 1804800 1939200 120
  set_hispeed_freq 0 0
  gpu_pl_up 1
  sched_boost 0
  stune_top_app 0 0
  cpu0_core_ctl off
  cpu6_core_ctl off
  sched_config 60 78
  sched_limit 2000 1000 0 0
  cpuset '0-1' '0-3' '0-5' '0-7'
  ufshc_perf on

elif [[ "$action" = "fast" ]]; then
  # Custom fallback; the user's own governor/IO/GPU preferences are applied by
  # the options layer on top of this profile.
  set_cpu_governor_scenario 0 balance
  set_cpu_governor_scenario 6 balance
  set_gpu_governor_scenario gpu
  set_cpu_freq 1708800 1804800 1209600 2304000
  set_input_boost_freq 1804800 1939200 500
  set_hispeed_freq 0 0
  gpu_pl_up 2
  sched_boost 2
  stune_top_app 1 20
  cpu0_core_ctl off
  cpu6_core_ctl off
  sched_config 50 75
  sched_limit 5000 2000 0 0
  cpuset '0-1' '0-3' '0-5' '0-7'
  ufshc_perf on

elif [[ "$action" = "pedestal" ]]; then
  set_cpu_governor_scenario 0 balance
  set_cpu_governor_scenario 6 balance
  set_gpu_governor_scenario gpu
  set_cpu_freq 1804800 1804800 2304000 2304000
  set_input_boost_freq 0 0 0
  set_hispeed_freq 0 0
  gpu_pl_up 4
  sched_boost 2
  stune_top_app 1 100
  cpu0_core_ctl off
  cpu6_core_ctl off
  sched_config 57 75
  sched_limit 8000 8000 0 0
  cpuset '0-1' '0-3' '0-7' '0-7'
  ufshc_perf on

fi

