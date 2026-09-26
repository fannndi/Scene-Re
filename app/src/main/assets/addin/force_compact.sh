level="$1" # Reclaim level (0: minimal, 1: light, 2: heavier, 3: extreme)

# Messages
kernel_unsupported="@string:home_shell_01"
swap_too_low="@string:home_shell_02"
prohibit_parallel="@string:home_shell_03"
reclaim_completed="@string:home_shell_04"
calculation_error="@string:home_shell_05"
memory_enough="@string:home_shell_06"
write_back_completed="@string:home_shell_05"

# Level 0 runs during live boosting, usually under high memory load where the
# cache is already small, so dropping caches first adds nothing and is skipped.
if [[ "$level" != "0" ]]; then
  echo 3 > /proc/sys/vm/drop_caches
fi

modify_path=''
friendly=false

if [[ -f '/proc/sys/vm/extra_free_kbytes' ]]; then
  modify_path='/proc/sys/vm/extra_free_kbytes'
  friendly=true
elif [[ -f '/proc/sys/vm/min_free_kbytes' ]]; then
  modify_path='/proc/sys/vm/min_free_kbytes'
else
  echo $kernel_unsupported
  return 1
fi

min_free_kbytes=`getprop vtools.backup.free_kbytes`
if [[ $min_free_kbytes == '' ]]; then
  min_free_kbytes=`cat $modify_path`
  setprop vtools.backup.free_kbytes $min_free_kbytes
fi

MemTotalStr=`cat /proc/meminfo | grep MemTotal`
MemTotal=${MemTotalStr:16:8}

MemMemFreeStr=`cat /proc/meminfo | grep MemFree`
MemMemFree=${MemMemFreeStr:16:8}

SwapFreeStr=`cat /proc/meminfo | grep SwapFree`
SwapFree=${SwapFreeStr:16:8}

if [[ "$level" == "3" ]]; then
  if [[ $friendly == "true" ]]; then
    TargetRecycle=$(($MemTotal / 100 * 55))
  else
    TargetRecycle=$(($MemTotal / 100 * 26))
  fi
elif [[ "$level" == "2" ]]; then
  if [[ $friendly == "true" ]]; then
    TargetRecycle=$(($MemTotal / 100 * 35))
  else
    TargetRecycle=$(($MemTotal / 100 * 18))
  fi
elif [[ "$level" == "0" ]]; then
  if [[ $friendly == "true" ]]; then
    TargetRecycle=$(($MemTotal / 100 * 14))
  else
    TargetRecycle=$(($MemTotal / 100 * 10))
  fi
else
  if [[ $friendly == "true" ]]; then
    TargetRecycle=$(($MemTotal / 100 * 20))
  else
    TargetRecycle=$(($MemTotal / 100 * 12))
  fi
fi

zram_writback() {
  if [[ ! -f /sys/block/zram0/backing_dev ]] || [[ $(cat /proc/swaps | grep zram0) == '' ]]; then
    return 0
  fi
  backing_dev=$(cat /sys/block/zram0/backing_dev)
  if [[ "$backing_dev" != '' ]] && [[ "$backing_dev" != 'none' ]]; then
    echo all > /sys/block/zram0/idle
    echo idle > /sys/block/zram0/writeback

    MemMemFree=${MemMemFreeStr:16:8}
    if [[ $MemMemFree -gt $TargetRecycle ]]; then
      return 1
    fi
  fi
  return 0
}

force_reclaim() {
  # How much memory has to be reclaimed
  RecyclingSize=$(($TargetRecycle - $MemMemFree))

  # SWAP capacity needed to reclaim it
  SwapRequire=$(($RecyclingSize / 100 * 130))

  # Without enough SWAP for the whole job, reclaim only 50% of what is free.
  if [[ $SwapFree -lt $SwapRequire ]]; then
    # Mode 0 favors performance: forcing reclaim with low SWAP is risky, so skip it.
    if [[ "$level" == "0" ]]; then
      echo $swap_too_low
      return 5
    fi
    RecyclingSize=$(($SwapFree / 100 * 50))
  fi

  # Final target free-memory value
  TargetRecycle=$(($RecyclingSize + $MemMemFree))

  if [[ $RecyclingSize != "" ]] && [[ $RecyclingSize -gt 0 ]]; then
    running_tag=`getprop vtools.state.force_compact`
    # Guard: only one reclaim at a time
    if [[ "$running_tag" == "1" ]]; then
      echo $prohibit_parallel
      return 0
    else
      setprop vtools.state.force_compact 1
    fi

    echo $TargetRecycle > $modify_path
    # Level 0 (live boost) must stay smooth: shorten the reclaim window to avoid jank
    if [[ "$level" == "0" ]]; then
      # TODO: remove the log
      current_app=`getprop vtools.powercfg_app`
      echo $current_app $(($RecyclingSize / 1024))MB >> /cache/force_compact.log
      sleep_time=$(($RecyclingSize / 1024 / 120 + 2))
      if [[ $sleep_time -gt 6 ]]; then
        sleep_time=6
      fi
    else
      echo Scene App $(($RecyclingSize / 1024))MB >> /cache/force_compact.log
      sleep_time=$(($RecyclingSize / 1024 / 60 + 2))
    fi

    while [ $sleep_time -gt 0 ]; do
      sleep 1
      MemMemFreeStr=`cat /proc/meminfo | grep MemFree`
      MemMemFree=${MemMemFreeStr:16:8}

      # Enough memory reclaimed: stop early
      if [[ $(($TargetRecycle - $MemMemFree)) -lt 100 ]]; then
        break
      fi

      SwapFreeStr=`cat /proc/meminfo | grep SwapFree`
      SwapFree=${SwapFreeStr:16:8}
      # SWAP nearly exhausted: stop early
      if [[ $SwapFree -lt 100 ]]; then
        break
      fi

      # Otherwise keep waiting out the countdown
      sleep_time=$(expr $sleep_time - 1)
    done

    # Restore the original setting
    echo $min_free_kbytes > $modify_path
    echo $reclaim_completed

    # Clear the running flag
    setprop vtools.state.force_compact 0
  else
    echo $calculation_error
  fi
}

# Free memory already above the target: nothing to reclaim
if [[ $MemMemFree -gt $TargetRecycle ]]; then
  echo $memory_enough
else
  zram_writback

  if [[ "$?" == '1' ]]; then
    echo $write_back_completed
  else
    force_reclaim
  fi
fi

if [[ -f /proc/sys/vm/compact_memory ]]; then
  echo 1 > /proc/sys/vm/compact_memory
fi
