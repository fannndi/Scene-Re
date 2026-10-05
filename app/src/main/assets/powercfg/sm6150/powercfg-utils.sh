# Scene-Re powercfg 工具库
# - 频率/调度/GPU/IO 原语
# - profiles/*.json 解析（load_profile_json）

# GPU频率表（升序）
gpu_freqs=`cat /sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies`
# GPU频率表（降序，索引即 power level）
gpu_pwrlevel_freqs=`cat /sys/class/kgsl/kgsl-3d0/gpu_available_frequencies`
# GPU最大频率
gpu_max_freq='700000000'
# GPU最小频率
gpu_min_freq='180000000'
# GPU最小 power level
gpu_min_pl=6
# GPU最大 power level
gpu_max_pl=0

# MaxFrequency、MinFrequency
for freq in $gpu_freqs; do
  if [[ $freq -gt $gpu_max_freq ]]; then
    gpu_max_freq=$freq
  fi;
  if [[ $freq -lt $gpu_min_freq ]]; then
    gpu_min_freq=$freq
  fi;
done

# Power Levels
if [[ -f /sys/class/kgsl/kgsl-3d0/num_pwrlevels ]];then
  gpu_min_pl=`cat /sys/class/kgsl/kgsl-3d0/num_pwrlevels`
  gpu_min_pl=`expr $gpu_min_pl - 1`
fi;
if [[ "$gpu_min_pl" -lt 0 ]];then
  gpu_min_pl=0
fi

core_online=(1 1 1 1 1 1 1 1)
set_core_online() {
  for index in 0 1 2 3 4 5 6 7; do
    core_online[$index]=`cat /sys/devices/system/cpu/cpu$index/online`
    echo 1 > /sys/devices/system/cpu/cpu$index/online
  done
}
restore_core_online() {
  for i in "${!core_online[@]}"; do
     echo ${core_online[i]} > /sys/devices/system/cpu/cpu$i/online
  done
}

reset_basic_governor() {
  stop_scene_scheduler
  set_core_online

  # CPU
  governor0=`cat /sys/devices/system/cpu/cpufreq/policy0/scaling_governor`
  governor6=`cat /sys/devices/system/cpu/cpufreq/policy6/scaling_governor`

  if [[ ! "$governor0" = "schedutil" ]]; then
    echo 'schedutil' > /sys/devices/system/cpu/cpufreq/policy0/scaling_governor
  fi
  if [[ ! "$governor6" = "schedutil" ]]; then
    echo 'schedutil' > /sys/devices/system/cpu/cpufreq/policy6/scaling_governor
  fi

  # GPU
  gpu_governor=`cat /sys/class/kgsl/kgsl-3d0/devfreq/governor`
  if [[ ! "$gpu_governor" = "msm-adreno-tz" ]]; then
    echo 'msm-adreno-tz' > /sys/class/kgsl/kgsl-3d0/devfreq/governor
  fi
  echo $gpu_min_freq > /sys/class/kgsl/kgsl-3d0/devfreq/min_freq
  echo $gpu_min_pl > /sys/class/kgsl/kgsl-3d0/min_pwrlevel
  echo $gpu_min_pl > /sys/class/kgsl/kgsl-3d0/default_pwrlevel 2>/dev/null
  echo $gpu_max_pl > /sys/class/kgsl/kgsl-3d0/max_pwrlevel
}

devfreq_performance () {
  bw_max_always
}

devfreq_restore () {
  bw_min
}

bw_min() {
  local path='/sys/class/devfreq/soc:qcom,cpu-llcc-ddr-bw'
  cat $path/available_frequencies | awk -F ' ' '{print $1}' > $path/min_freq

  local path='/sys/class/devfreq/soc:qcom,cpu-cpu-llcc-bw'
  cat $path/available_frequencies | awk -F ' ' '{print $1}' > $path/min_freq
}

bw_max() {
  local path='/sys/class/devfreq/soc:qcom,cpu-llcc-ddr-bw'
  cat $path/available_frequencies | awk -F ' ' '{print $NF}' > $path/max_freq

  local path='/sys/class/devfreq/soc:qcom,cpu-cpu-llcc-bw'
  cat $path/available_frequencies | awk -F ' ' '{print $NF}' > $path/max_freq
}

bw_max_always() {
  local path='/sys/class/devfreq/soc:qcom,cpu-llcc-ddr-bw'
  local b_max=`cat $path/available_frequencies | awk -F ' ' '{print $NF}'`
  echo $b_max > $path/min_freq
  echo $b_max > $path/max_freq
  echo $b_max > $path/min_freq

  local path='/sys/class/devfreq/soc:qcom,cpu-cpu-llcc-bw'
  local b_max=`cat $path/available_frequencies | awk -F ' ' '{print $NF}'`
  echo $b_max > $path/min_freq
  echo $b_max > $path/max_freq
  echo $b_max > $path/min_freq
}

set_value() {
  value=$1
  path=$2
  if [[ -f $path ]]; then
    current_value="$(cat $path)"
    if [[ ! "$current_value" = "$value" ]]; then
      chmod 0664 "$path"
      echo "$value" > "$path"
    fi;
  fi;
}

set_input_boost_freq() {
  local c0="$1"
  local c1="$2"
  local ms="$3"
  echo "0:$c0 1:$c0 2:$c0 3:$c0 4:$c0 5:$c0 6:$c1 7:$c1" > /sys/module/cpu_boost/parameters/input_boost_freq
  echo $ms > /sys/module/cpu_boost/parameters/input_boost_ms
  if [[ "$ms" -gt 0 ]]; then
    echo 1 > /sys/module/cpu_boost/parameters/sched_boost_on_input
  else
    echo 0 > /sys/module/cpu_boost/parameters/sched_boost_on_input
  fi
}

# 设置 CPU 频率上限/下限
# 先放开 min 再抬/压 max，最后落目标 min —— 避免被旧值钳制
set_cpu_freq() {
  echo "0:4294967295 1:4294967295 2:4294967295 3:4294967295 4:4294967295 5:4294967295 6:4294967295 7:4294967295" > /sys/module/msm_performance/parameters/cpu_max_freq
  echo "0:0 1:0 2:0 3:0 4:0 5:0 6:0 7:0" > /sys/module/msm_performance/parameters/cpu_min_freq

  set_value 300000 /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq
  set_value 300000 /sys/devices/system/cpu/cpufreq/policy6/scaling_min_freq

  set_value $2 /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq
  set_value $4 /sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq

  set_value $1 /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq
  set_value $3 /sys/devices/system/cpu/cpufreq/policy6/scaling_min_freq
}

sched_config() {
  echo "$1" > /proc/sys/kernel/sched_downmigrate
  echo "$2" > /proc/sys/kernel/sched_upmigrate
  echo "$1" > /proc/sys/kernel/sched_downmigrate
  echo "$2" > /proc/sys/kernel/sched_upmigrate

  echo "$3" > /proc/sys/kernel/sched_group_downmigrate
  echo "$4" > /proc/sys/kernel/sched_group_upmigrate
  echo "$3" > /proc/sys/kernel/sched_group_downmigrate
  echo "$4" > /proc/sys/kernel/sched_group_upmigrate
}

sched_limit() {
  echo $1 > /sys/devices/system/cpu/cpufreq/policy0/schedutil/down_rate_limit_us
  echo $2 > /sys/devices/system/cpu/cpufreq/policy0/schedutil/up_rate_limit_us
  echo $3 > /sys/devices/system/cpu/cpufreq/policy6/schedutil/down_rate_limit_us
  echo $4 > /sys/devices/system/cpu/cpufreq/policy6/schedutil/up_rate_limit_us
}

set_cpu_pl() {
  echo $1 > /sys/devices/system/cpu/cpufreq/policy0/schedutil/pl
  echo $1 > /sys/devices/system/cpu/cpufreq/policy6/schedutil/pl
}

set_gpu_min_freq() {
  index=$1

  # GPU频率表
  gpu_freqs=`cat /sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies`

  target_freq=$(echo $gpu_freqs | awk "{print \$${index}}")
  if [[ "$target_freq" != "" ]]; then
    echo $target_freq > /sys/class/kgsl/kgsl-3d0/devfreq/min_freq
  fi
}

cpu6_core_ctl(){
  cpu6_core_ctl_dir=/sys/devices/system/cpu/cpu6/core_ctl
  if [[ "$1" == "on" ]];then
    echo 10 > $cpu6_core_ctl_dir/offline_delay_ms
    echo 1 1 > $cpu6_core_ctl_dir/not_preferred
    echo 1 > $cpu6_core_ctl_dir/enable
    echo 2 > $cpu6_core_ctl_dir/max_cpus
    echo 0 > $cpu6_core_ctl_dir/min_cpus
    echo 2 > $cpu6_core_ctl_dir/task_thres
    echo 30 > $cpu6_core_ctl_dir/busy_down_thres
    echo 50 > $cpu6_core_ctl_dir/busy_up_thres
  else
    echo 0 > $cpu6_core_ctl_dir/enable
  fi
}
cpu0_core_ctl(){
  cpu0_core_ctl_dir=/sys/devices/system/cpu/cpu0/core_ctl
  if [[ "$1" == "on" ]];then
    echo 50 > $cpu0_core_ctl_dir/offline_delay_ms
    echo 0 1 1 1 1 1 > $cpu0_core_ctl_dir/not_preferred
    echo 1 > $cpu0_core_ctl_dir/enable
    echo 6 > $cpu0_core_ctl_dir/max_cpus
    echo 1 > $cpu0_core_ctl_dir/min_cpus
    echo 5 > $cpu0_core_ctl_dir/busy_down_thres
    echo 15 > $cpu0_core_ctl_dir/busy_up_thres
  else
    echo 0 > $cpu0_core_ctl_dir/enable
  fi
}

# 内核默认 core_ctl（min 1 / max 全部 / offline 100ms / busy 阈值 0）
core_ctl_stock() {
  for c in 0 6; do
    local dir="/sys/devices/system/cpu/cpu$c/core_ctl"
    if [[ -d "$dir" ]]; then
      set_value 1 "$dir/enable"
      set_value 1 "$dir/min_cpus"
      set_value 100 "$dir/offline_delay_ms"
      if [[ "$c" == "6" ]]; then
        set_value 2 "$dir/max_cpus"
        set_value 0 "$dir/busy_up_thres"
        set_value 0 "$dir/busy_down_thres"
      else
        set_value 6 "$dir/max_cpus"
        set_value 0 "$dir/busy_up_thres"
        set_value 0 "$dir/busy_down_thres"
      fi
    fi
  done
}

# 按配置启用/关闭 core_ctl 并设置大核参数
core_ctl_apply() {
  local little="$1"
  local big="$2"
  local big_min="$3"
  local busy_up="$4"
  local busy_down="$5"

  if [[ "$little" == "on" ]]; then
    cpu0_core_ctl on
  else
    cpu0_core_ctl off
  fi

  if [[ "$big" == "on" ]]; then
    cpu6_core_ctl on
    set_value "$big_min" /sys/devices/system/cpu/cpu6/core_ctl/min_cpus
    set_value "$busy_up" /sys/devices/system/cpu/cpu6/core_ctl/busy_up_thres
    set_value "$busy_down" /sys/devices/system/cpu/cpu6/core_ctl/busy_down_thres
  else
    cpu6_core_ctl off
  fi
}

ctl_on() {
  echo 1 > /sys/devices/system/cpu/$1/core_ctl/enable
  if [[ "$2" != "" ]]; then
    echo $2 > /sys/devices/system/cpu/$1/core_ctl/min_cpus
  else
    echo 0 > /sys/devices/system/cpu/$1/core_ctl/min_cpus
  fi
}

ctl_off() {
  echo 0 > /sys/devices/system/cpu/$1/core_ctl/enable
}

set_ctl() {
  echo $2 > /sys/devices/system/cpu/$1/core_ctl/busy_up_thres
  echo $3 > /sys/devices/system/cpu/$1/core_ctl/busy_down_thres
  echo $4 > /sys/devices/system/cpu/$1/core_ctl/offline_delay_ms
}

set_hispeed_freq() {
  echo $1 > /sys/devices/system/cpu/cpufreq/policy0/schedutil/hispeed_freq
  echo $2 > /sys/devices/system/cpu/cpufreq/policy6/schedutil/hispeed_freq
}

set_hispeed_load() {
  echo $1 > /sys/devices/system/cpu/cpufreq/policy0/schedutil/hispeed_load
  echo $2 > /sys/devices/system/cpu/cpufreq/policy6/schedutil/hispeed_load
}

sched_boost() {
  echo $1 > /proc/sys/kernel/sched_boost_top_app
  echo $2 > /proc/sys/kernel/sched_boost
}

stune_top_app() {
  echo $1 > /dev/stune/top-app/schedtune.prefer_idle
  echo $2 > /dev/stune/top-app/schedtune.boost
}

cpuset() {
  # MIUI 的 perfservice 会拦截部分 cpuset（如 background/foreground），
  # 写入失败属预期，静默处理即可
  echo $1 > /dev/cpuset/background/cpus 2>/dev/null
  echo $2 > /dev/cpuset/system-background/cpus 2>/dev/null
  echo $3 > /dev/cpuset/foreground/cpus 2>/dev/null
  echo $4 > /dev/cpuset/top-app/cpus 2>/dev/null
}

# [min/max/def] pl(number)
set_gpu_pl(){
  echo $2 > /sys/class/kgsl/kgsl-3d0/${1}_pwrlevel
}

set_gpu_max_freq () {
  echo $1 > /sys/class/kgsl/kgsl-3d0/devfreq/max_freq
  local pl=-1

  for freq in $gpu_freqs; do
    local pl=$((pl + 1))
    if [[ $freq -lt $1 ]] || [[ $freq == $1 ]]; then
      break
    fi;
  done
  if [[ $pl -gt -1 ]]; then
    echo $pl > /sys/class/kgsl/kgsl-3d0/max_pwrlevel
  fi
}

# GPU MinPowerLevel To Up
gpu_pl_up() {
  local offset="$1"
  if [[ "$offset" != "" ]] && [[ ! "$offset" -gt "$gpu_min_pl" ]]; then
    echo `expr $gpu_min_pl - $offset` > /sys/class/kgsl/kgsl-3d0/min_pwrlevel
  elif [[ "$offset" -gt "$gpu_min_pl" ]]; then
    echo 0 > /sys/class/kgsl/kgsl-3d0/min_pwrlevel
  else
    echo $gpu_min_pl > /sys/class/kgsl/kgsl-3d0/min_pwrlevel
  fi
}

# GPU MinPowerLevel To Down
gpu_pl_down() {
  local offset="$1"
  if [[ "$offset" != "" ]] && [[ ! "$offset" -gt "$gpu_min_pl" ]]; then
    echo $offset > /sys/class/kgsl/kgsl-3d0/max_pwrlevel
  elif [[ "$offset" -gt "$gpu_min_pl" ]]; then
    echo $gpu_min_pl > /sys/class/kgsl/kgsl-3d0/max_pwrlevel
  else
    echo $gpu_min_pl > /sys/class/kgsl/kgsl-3d0/max_pwrlevel
  fi
}

# power level 对应的频率（降序表索引）
gpu_freq_of_pl() {
  local pl="$1"
  local i=0
  for f in $gpu_pwrlevel_freqs; do
    if [[ "$i" == "$pl" ]]; then
      echo "$f"
      return
    fi
    i=$((i + 1))
  done
  echo ""
}

# 设置 GPU 频率上限/下限（同时写 pwrlevel 与 devfreq）
set_gpu_pwrlevels() {
  local max_pl="$1"
  local min_pl="$2"

  # min_pwrlevel 不能低于 max_pwrlevel（索引越大频率越低）
  if [[ "$min_pl" -lt "$max_pl" ]]; then
    min_pl="$max_pl"
  fi

  local max_freq=`gpu_freq_of_pl "$max_pl"`
  local min_freq=`gpu_freq_of_pl "$min_pl"`

  if [[ "$max_freq" != "" ]]; then
    set_value "$max_freq" /sys/class/kgsl/kgsl-3d0/devfreq/max_freq
    set_value "$max_pl" /sys/class/kgsl/kgsl-3d0/max_pwrlevel
  fi
  if [[ "$min_freq" != "" ]]; then
    set_value "$min_freq" /sys/class/kgsl/kgsl-3d0/devfreq/min_freq
    set_value "$min_pl" /sys/class/kgsl/kgsl-3d0/min_pwrlevel
  fi
}

set_gpu_governor() {
  local governor="$1"
  if [[ "$governor" == "" ]]; then
    return
  fi
  local current=`cat /sys/class/kgsl/kgsl-3d0/devfreq/governor`
  if [[ "$current" != "$governor" ]]; then
    set_value "$governor" /sys/class/kgsl/kgsl-3d0/devfreq/governor
  fi
}

# 总线带宽策略：min / max / always
set_devfreq_bw() {
  case "$1" in
    max) bw_max ;;
    always) bw_max_always ;;
    *) bw_min ;;
  esac
}

# GPU 总线带宽下限（gpubw）
set_gpubw_floor() {
  local path='/sys/class/devfreq/soc:qcom,gpubw'
  if [[ ! -d "$path" ]]; then
    return
  fi
  if [[ "$1" == "on" ]]; then
    local b_max=`cat $path/available_frequencies | awk -F ' ' '{print $NF}'`
    set_value "$b_max" $path/min_freq
  else
    local b_min=`cat $path/available_frequencies | awk -F ' ' '{print $1}'`
    set_value "$b_min" $path/min_freq
  fi
}

# 块设备 IO：scheduler / read_ahead / nr_requests / iostats
set_block_io() {
  local sched="$1"
  local read_ahead="$2"
  local nr_requests="$3"
  local iostats="$4"

  for dev in sda sdb sdc sdd sde sdf mmcblk0; do
    local queue="/sys/block/$dev/queue"
    if [[ ! -d "$queue" ]]; then
      continue
    fi
    if [[ "$sched" != "" && -f "$queue/scheduler" ]]; then
      case "`cat $queue/scheduler`" in
        *"$sched"*) set_value "$sched" "$queue/scheduler" ;;
      esac
    fi
    if [[ "$read_ahead" != "" && -f "$queue/read_ahead_kb" ]]; then
      set_value "$read_ahead" "$queue/read_ahead_kb"
    fi
    if [[ "$nr_requests" != "" && -f "$queue/nr_requests" ]]; then
      set_value "$nr_requests" "$queue/nr_requests"
    fi
    if [[ "$iostats" != "" && -f "$queue/iostats" ]]; then
      set_value "$iostats" "$queue/iostats"
    fi
  done
}

# 解析扁平 JSON 配置（{"key":value,...} 单层）
# 只接受白名单键名与安全值，避免脏数据进入 eval
load_profile_json() {
  local file="$1"
  if [[ ! -f "$file" ]]; then
    return 1
  fi

  local pairs
  pairs=$(tr -d '[:space:]' < "$file" \
    | sed 's/[{}]//g' \
    | tr ',' '\n' \
    | sed -n 's/^"\([A-Za-z0-9_]*\)":\(.*\)$/\1=\2/p' \
    | sed 's/"//g')

  local clean=""
  local line
  while IFS= read -r line; do
    local key="${line%%=*}"
    local val="${line#*=}"
    if [[ "$key" == "$line" || "$key" == "" || "$val" == "" ]]; then
      continue
    fi
    case "$key" in
      *[!A-Za-z0-9_]*) continue ;;
    esac
    case "$val" in
      *[!A-Za-z0-9_.:-]*) continue ;;
    esac
    clean="$clean$key=$val
"
  done <<EOF
$pairs
EOF

  if [[ "$clean" == "" ]]; then
    return 1
  fi

  eval "$clean"
  return 0
}

# set_task_affinity $pid $use_cores[cpu7~cpu0]
set_task_affinity() {
  pid=$1
  mask=`echo "obase=16;$((num=2#$2))" | bc`
  for tid in $(ls "/proc/$pid/task/"); do
    taskset -p "$mask" "$tid" 1>/dev/null
  done
  taskset -p "$mask" "$pid" 1>/dev/null
}

# YuanShen
yuan_shen_opt_run() {
  if [[ $(getprop vtools.powercfg_app | grep miHoYo) == "" ]]; then
    return
  fi

  pid=$(pgrep -ef miHoYo)

  if [[ "$pid" != "" ]]; then
    for tid in $(ls "/proc/$pid/task/"); do
      if [[ -f "/proc/$pid/task/$tid/comm" ]]; then
        comm=$(cat /proc/$pid/task/$tid/comm)

        case "$comm" in
         "UnityMain"|"UnityGfxDevice"*|"UnityMultiRende"*)
           # set cpu6-7
           taskset -p "C0" "$tid" > /dev/null 2>&1
         ;;
         *)
           # set cpu0-6
           taskset -p "3F" "$tid" > /dev/null 2>&1
         ;;
        esac
      fi
    done
  fi
}

# watch_app [on_tick] [on_change]
watch_app() {
  local interval=120
  local on_tick="$1"
  local on_change="$2"
  local app=$(getprop vtools.powercfg_app)

  if [[ "$on_tick" == "" ]]; then
    return
  fi

  if [[ "$app" == "" ]]; then
    return
  fi

  procs=$(pgrep -f com.omarea.*powercfg.sh)
  last_proc=$(echo "$procs" | tail -n 1)
  if [[ "$last_proc" != "" ]]; then
    echo "$procs" | grep -v "$last_proc" | while read pid; do
      kill -9 $pid 2> /dev/null
    done
  fi

  ticks=0
  while true
  do
    if [[ $ticks -gt 3 ]]; then
      sleep $interval
    elif [[ $ticks -gt 0 ]]; then
      sleep 30
    else
      sleep 10
    fi
    ticks=$((ticks + 1))

    current=$(getprop vtools.powercfg_app)
    if [[ "$current" == "$app" ]]; then
      $on_tick $current
    else
      if [[ "$on_change" ]]; then
        $on_change $current
      fi
      return
    fi
  done
}

stop_scene_scheduler(){
  killall 'scene-scheduler' 2>/dev/null
}
scene_scheduler() {
  SCDIR=${0%/*}
  killall 'scene-scheduler' 2>/dev/null
  $SCDIR/scene-scheduler -p="$1" -m="$2" -c="$SCDIR/profile.json" >/dev/null 2>&1 &
}
