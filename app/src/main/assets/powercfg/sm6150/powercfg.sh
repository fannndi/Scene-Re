#!/system/bin/sh
# Scene-Re powercfg 调度入口
#
# 用法（与旧脚本一致）：
#   sh powercfg.sh init                —— 应用基线（powercfg-base.sh）
#   sh powercfg.sh <mode> [task<id>]   —— 应用某个模式的调优（profiles/<mode>.json）
#   sh powercfg.sh screen_off          —— 息屏省电
#   sh powercfg.sh screen_on           —— 亮屏恢复当前模式
#
# 模式数据在 profiles/<mode>.json（用户编辑版优先，其次 profiles/default/）
# 环境变量 top_app 由调用方导出（动态响应严格模式）

action=$1
task=$2

cfg_dir=$(cd $(dirname $0); pwd)

if [[ ! -f "$cfg_dir/powercfg-utils.sh" ]]; then
  echo "The dependent '$cfg_dir/powercfg-utils.sh' was not found !" > /cache/powercfg.sh.log
  exit 1
fi

source "$cfg_dir/powercfg-utils.sh"

# 调优层级：basic = 只覆盖与 MIUI 服务不冲突的参数；pro = 全面接管
profile_tier=${profile_tier:-pro}
basic=false
if [[ "$profile_tier" == "basic" ]]; then
  basic=true
fi

# 仅在 pro 层级执行的调用
pro_only() {
  if [[ "$basic" == "false" ]]; then
    "$@"
  fi
}

if [[ "$action" == "init" ]]; then
  if [[ -f "$cfg_dir/powercfg-base.sh" ]]; then
    source "$cfg_dir/powercfg-base.sh"
  elif [[ -f '/data/powercfg-base.sh' ]]; then
    source /data/powercfg-base.sh
  fi
  exit 0
fi

# 恢复 ROM/内核默认（Profile Service 关闭时使用）
# 注意：恢复后 MIUI 会按自己的策略继续管理，这正是“默认 ROM”行为
stock_restore() {
  stop_scene_scheduler

  # CPU：完整频率范围 + 内核默认 schedutil 参数
  set_cpu_freq 300000 1804800 300000 2304000
  set_input_boost_freq 1324800 1324800 40
  set_hispeed_freq 0 0
  set_hispeed_load 90 90
  sched_boost 0 0
  stune_top_app 0 0
  sched_config 65 71 85 100
  sched_limit 10000 10000 10000 10000

  # core_ctl：内核默认
  core_ctl_stock

  # cpuset：ROM 默认
  cpuset 0-1 0-4 0-7 0-7

  # GPU
  set_gpu_governor msm-adreno-tz
  set_gpu_pwrlevels 0 6

  # 总线
  bw_min
  set_gpubw_floor off

  # 块设备：ROM 默认（cfq）
  set_block_io cfq 128 64 1
}

if [[ "$action" == "stock" ]]; then
  stock_restore
  exit 0
fi

# 兜底默认值（JSON 缺键时使用）
set_defaults() {
  little_min=300000; little_max=1804800
  big_min=300000; big_max=2304000
  hispeed_little=1708800; hispeed_big=1324800
  hispeed_load_little=90; hispeed_load_big=85
  rate_limit_little_down=1000; rate_limit_little_up=0
  rate_limit_big_down=1000; rate_limit_big_up=0
  input_boost_little=1708800; input_boost_big=1324800; input_boost_ms=0
  sched_boost_top_app=1; sched_boost=0
  stune_prefer_idle=0; stune_boost=0
  sched_down=70; sched_up=85; sched_group_down=300; sched_group_up=400
  core_ctl_little=off; core_ctl_big=off
  core_ctl_big_min=0; core_ctl_big_busy_up=50; core_ctl_big_busy_down=30
  cpuset_bg=0-1; cpuset_sysbg=0-3; cpuset_fg=0-5; cpuset_top=0-7
  gpu_governor=msm-adreno-tz; gpu_max_pl=0; gpu_min_pl=6; gpu_bw_floor=off
  blk_scheduler=noop; read_ahead_kb=256; nr_requests=64; iostats=0
  devfreq_bw=min
}

# 解析动作
screen_off=false
case "$action" in
  screen_off)
    screen_off=true
    profile="screen_off"
    ;;
  screen_on)
    profile=`getprop vtools.powercfg`
    if [[ "$profile" == "" || "$profile" == "screen_off" ]]; then
      profile="balance"
    fi
    action="$profile"
    ;;
  *)
    profile="$action"
    ;;
esac

set_defaults

# 用户编辑版优先，其次内置默认
if ! load_profile_json "$cfg_dir/profiles/$profile.json"; then
  if ! load_profile_json "$cfg_dir/profiles/default/$profile.json"; then
    load_profile_json "$cfg_dir/profiles/default/balance.json" || exit 1
  fi
fi

apply_profile() {
  pro_only set_cpu_freq "$little_min" "$little_max" "$big_min" "$big_max"
  set_input_boost_freq "$input_boost_little" "$input_boost_big" "$input_boost_ms"
  set_hispeed_freq "$hispeed_little" "$hispeed_big"
  set_hispeed_load "$hispeed_load_little" "$hispeed_load_big"
  sched_boost "$sched_boost_top_app" "$sched_boost"
  stune_top_app "$stune_prefer_idle" "$stune_boost"
  sched_config "$sched_down" "$sched_up" "$sched_group_down" "$sched_group_up"
  sched_limit "$rate_limit_little_down" "$rate_limit_little_up" "$rate_limit_big_down" "$rate_limit_big_up"
  pro_only core_ctl_apply "$core_ctl_little" "$core_ctl_big" "$core_ctl_big_min" "$core_ctl_big_busy_up" "$core_ctl_big_busy_down"
  pro_only cpuset "$cpuset_bg" "$cpuset_sysbg" "$cpuset_fg" "$cpuset_top"
  set_devfreq_bw "$devfreq_bw"
  set_gpubw_floor "$gpu_bw_floor"
  set_gpu_governor "$gpu_governor"
  set_gpu_pwrlevels "$gpu_max_pl" "$gpu_min_pl"
  set_block_io "$blk_scheduler" "$read_ahead_kb" "$nr_requests" "$iostats"
}

# 针对特定应用的补充调优（按 top_app）
adjustment_by_top_app() {
  case "$top_app" in
    # YuanShen
    "com.miHoYo.Yuanshen" | "com.miHoYo.ys.mi" | "com.miHoYo.ys.bilibili")
      set_hispeed_freq 0 0
      devfreq_performance
      if [[ "$action" = "powersave" ]]; then
        sched_boost 0 0
        stune_top_app 0 0
        sched_config "50 80" "67 95" "300" "400"
        gpu_pl_up 2
        sched_limit 5000 0 5000 0
        pro_only set_cpu_freq 1708800 1804800 1708800 2304000
      elif [[ "$action" = "balance" ]]; then
        sched_boost 1 0
        stune_top_app 0 20
        sched_config "50 68" "67 80" "300" "400"
        gpu_pl_up 2
        sched_limit 5000 0 5000 0
        pro_only set_cpu_freq 1804800 1804800 1939200 2304000
      elif [[ "$action" = "performance" ]]; then
        sched_boost 1 0
        stune_top_app 0 100
        gpu_pl_up 3
        sched_limit 5000 0 5000 0
        pro_only set_cpu_freq 1804800 1804800 2169600 2304000
      elif [[ "$action" = "fast" ]]; then
        sched_boost 1 0
        stune_top_app 0 100
        gpu_pl_up 3
        sched_limit 5000 0 10000 0
        pro_only set_cpu_freq 1804800 1804800 2208000 2304000
      elif [[ "$action" = "pedestal" ]]; then
        sched_boost 1 0
        stune_top_app 0 100
      fi
      pro_only cpuset '0' '0' '0-7' '0-7'
    ;;

    # Wang Zhe Rong Yao
    "com.tencent.tmgp.sgame")
      pro_only ctl_off cpu0
      pro_only ctl_off cpu6
      set_hispeed_freq 0 0
      pro_only cpuset '0' '0' '0-7' '0-7'
      if [[ "$action" = "powersave" ]]; then
        sched_config "52 55" "69 67" "300" "400"
        sched_boost 1 0
        stune_top_app 0 10
        pro_only set_cpu_freq 1708800 1804800 1209600 2304000
      elif [[ "$action" = "balance" ]]; then
        sched_config "50 55" "65 65" "300" "400"
        sched_boost 1 0
        stune_top_app 0 30
        pro_only set_cpu_freq 1804800 1804800 1708800 2304000
      elif [[ "$action" = "performance" ]]; then
        sched_config "45 55" "55 65" "300" "400"
        sched_boost 1 0
        stune_top_app 0 100
        pro_only set_cpu_freq 1804800 1804800 1939200 2304000
      elif [[ "$action" = "fast" ]]; then
        sched_config "40 55" "50 63" "300" "400"
        sched_boost 1 2
        stune_top_app 0 100
        pro_only set_cpu_freq 1804800 1804800 2208000 2304000
      elif [[ "$action" = "pedestal" ]]; then
        sched_boost 1 0
        stune_top_app 0 100
      fi
    ;;

    # XianYu, TaoBao, Browser, TieBa, JingDong, TianMao, MeiTuan, PuPuChaoShi
    "com.taobao.idlefish" | "com.taobao.taobao" | "com.android.browser" | "com.baidu.tieba_mini" | "com.baidu.tieba" | "com.jingdong.app.mall" | "com.tmall.wireless" | "com.sankuai.meituan" | "com.pupumall.customer")
      if [[ "$action" == "powersave" ]]; then
        sched_config "45 62" "55 75" "85" "100"
      else
        sched_boost 1 2
        stune_top_app 1 1
        sched_config "45 62" "55 75" "85" "100"
      fi
    ;;

    "com.speedsoftware.rootexplorer" | "com.estrongs.android.pop")
      if [[ "$action" == "powersave" ]]; then
        sched_config "45 62" "55 75" "85" "100"
      elif [[ "$action" == "balance" ]]; then
        sched_config "40 50" "50 65" "85" "100"
      elif [[ "$action" == "performance" ]]; then
        sched_boost 1 0
        stune_top_app 1 1
        sched_config "40 50" "50 65" "85" "100"
      else
        sched_boost 1 2
        stune_top_app 1 1
        sched_config "40 50" "50 65" "85" "100"
      fi
    ;;

    "com.miui.home")
      if [[ "$action" == "powersave" ]]; then
        sched_config "45 62" "55 75" "85" "100"
      elif [[ "$action" == "balance" ]]; then
        sched_config "40 50" "50 65" "85" "100"
      elif [[ "$action" == "performance" ]]; then
        sched_config "35 52" "45 65" "65" "80"
      else
        sched_boost 1 2
        stune_top_app 1 1
        sched_config "45 62" "55 75" "85" "100"
      fi
    ;;

    # NeteaseCloudMusic, KuGou, KuGou Lite
    "com.netease.cloudmusic" | "com.kugou.android" | "com.kugou.android.lite")
      pro_only sh -c 'echo 0-6 > /dev/cpuset/foreground/cpus'
    ;;

    # DouYin, BiliBili
    "com.ss.android.ugc.aweme"|"com.ss.android.ugc.aweme.lite"|"tv.danmaku.bili")
      pro_only ctl_on cpu0
      pro_only ctl_on cpu7
      pro_only sh -c 'echo 0-3 > /dev/cpuset/foreground/cpus'

      if [[ "$action" = "powersave" ]]; then
        sched_boost 0 0
        stune_top_app 0 0
        pro_only sh -c 'echo 0-5 > /dev/cpuset/top-app/cpus'
      elif [[ "$action" = "balance" ]]; then
        sched_boost 0 0
        stune_top_app 0 0
        pro_only sh -c 'echo 0-7 > /dev/cpuset/top-app/cpus'
      elif [[ "$action" = "performance" ]]; then
        sched_boost 1 0
        stune_top_app 1 0
        pro_only sh -c 'echo 0-7 > /dev/cpuset/top-app/cpus'
      elif [[ "$action" = "fast" ]]; then
        sched_boost 1 2
        stune_top_app 1 10
        pro_only sh -c 'echo 0-7 > /dev/cpuset/top-app/cpus'
      fi

      sched_config "85 85" "100 100" "240" "400"
    ;;
  esac
  scene_scheduler "$top_app" "$action"
}

if [[ "$profile" == "fast" || "$profile" == "pedestal" ]]; then
  devfreq_performance
else
  devfreq_restore
fi
reset_basic_governor

apply_profile

# 息屏时不跑应用相关调整，也不重启 scene-scheduler
if [[ "$screen_off" == "false" ]]; then
  adjustment_by_top_app
fi
