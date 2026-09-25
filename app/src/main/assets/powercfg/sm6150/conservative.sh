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
  set_cpu_freq 5000 1612800 5000 1555200
  set_input_boost_freq 0 0 0
  set_hispeed_freq 1248000 806400
  sched_boost 0
  stune_top_app 0 0
  cpu0_core_ctl off
  cpu6_core_ctl on
  sched_config 85 96
  sched_limit 0 500 0 1000
  cpuset '0-1' '0-3' '0-3' '0-7'
  ufshc_perf off

elif [[ "$action" = "balance" ]]; then
  set_cpu_freq 5000 1708800 5000 1708800
  set_input_boost_freq 0 0 0
  set_hispeed_freq 1248000 1209600
  sched_boost 0
  stune_top_app 0 0
  cpu0_core_ctl off
  cpu6_core_ctl off
  sched_config 70 85
  sched_limit 0 0 0 1000
  cpuset '0-1' '0-3' '0-5' '0-7'
  ufshc_perf off

elif [[ "$action" = "light" ]]; then
  # Light games: no performance scheduler, no input boost and no UFS boost.
  # The optional light-game frequency caps are applied by the options layer on
  # top of this profile.
  set_cpu_freq 5000 1708800 5000 1708800
  set_input_boost_freq 0 0 0
  set_hispeed_freq 1248000 1209600
  sched_boost 0
  stune_top_app 1 0
  cpu0_core_ctl off
  cpu6_core_ctl off
  sched_config 74 88
  sched_limit 2000 1000 0 1000
  cpuset '0-1' '0-3' '0-5' '0-7'
  ufshc_perf off

elif [[ "$action" = "performance" ]]; then
  set_cpu_freq 300000 1804800 300000 2304000
  set_input_boost_freq 0 0 0
  set_hispeed_freq 1708800 1209600
  gpu_pl_up 1
  sched_boost 0
  stune_top_app 1 0
  cpu0_core_ctl off
  cpu6_core_ctl off
  sched_config 60 78
  sched_limit 1000 0 0 0
  cpuset '0-1' '0-3' '0-5' '0-7'
  ufshc_perf on

elif [[ "$action" = "fast" ]]; then
  set_cpu_freq 1708800 1804800 1209600 2304000
  set_input_boost_freq 1804800 1939200 120
  set_hispeed_freq 0 0
  gpu_pl_up 2
  sched_boost 2
  stune_top_app 1 30
  cpu0_core_ctl off
  cpu6_core_ctl off
  sched_config 57 75
  sched_limit 3000 2000 0 0
  cpuset '0-1' '0-3' '0-7' '0-7'
  ufshc_perf on

elif [[ "$action" = "pedestal" ]]; then
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

