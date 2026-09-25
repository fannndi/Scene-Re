#!/system/bin/sh
#
# zram helpers.
#
# NOTE: this file is currently NOT wired to any UI. The live zram path goes
# through addin/swap_control.sh plus SwapModuleUtils (config file + props). It
# is kept as a library for the swap page and for manual/custom commands, so the
# behaviour below has to be safe to call on a stock MIUI install.
#
# Two rules, both learned from the MIUI 14 surya ROM:
#
#  1. Never shrink zram. MIUI sizes it from
#     system/system/etc/perfinit.conf ("zram_size": 4096 for a 6 GB device) and
#     the fstab asks for 1 GiB as a baseline. Cutting that down to a hardcoded
#     768 MB removes most of the swap the platform budgeted for, which is a
#     direct OOM risk on a ROM whose LMK and process freezer are already
#     aggressive. Only ever grow.
#  2. Pick the best algorithm the kernel advertises. lzo is the weakest of the
#     three msm-4.14 ships; zstd compresses best and lz4 is the fastest. Hard
#     coding lzo throws away memory for no reason.

write() {
  echo -n "$2" > "$1"
}

# Echo the best algorithm from /sys/block/zram0/comp_algorithm, in the order
# zstd > lz4 > lzo. Falls back to lzo when the node is missing.
best_zram_algorithm() {
  local avail
  avail="$(cat /sys/block/zram0/comp_algorithm 2> /dev/null)"
  if [ -z "$avail" ]; then
    echo lzo
    return
  fi
  local algo
  for algo in zstd lz4 lzo; do
    case "$avail" in
      *"[$algo]"*) echo "$algo"; return ;;
      *"$algo"*)   echo "$algo"; return ;;
    esac
  done
  echo lzo
}

# Current zram size in MB (0 when zram is not set up).
zram_size_mb() {
  local disksize
  disksize="$(cat /sys/block/zram0/disksize 2> /dev/null)"
  [ -z "$disksize" ] && { echo 0; return; }
  echo $(( disksize / 1048576 ))
}

# 关闭当前所有的 zram（如果正在使用，那可不是一般的慢）
reset_all_zram() {
  local zram zram_dev dev_index
  for zram in $(blkid | grep swap | awk -F[/:] '{print $4}'); do
    zram_dev="/dev/block/${zram}"
    dev_index="$(echo "$zram" | grep -o "[0-9]*$")"
    swapoff "$zram_dev" 2> /dev/null
    write "/sys/block/$zram/reset" 1
    write "/sys/block/$zram/disksize" 0
    write "/sys/class/zram-control/hot_remove" "$dev_index"
  done
}

# enable_swap_props size(Byte)
enable_swap_props() {
  setprop vnswap.enabled true
  setprop ro.config.zram true
  setprop ro.config.zram.support true
  setprop zram.disksize "$1"
  write /proc/sys/vm/swappiness 100
  write /proc/sys/vm/swap_ratio_enable 1
  write /proc/sys/vm/swap_ratio 70
}

# 开启 zram
# $1 = target size in MB (optional; defaults to keeping the current size, or
#      4096 MB when zram is not set up yet).
enable_zram() {
  local want_mb="${1:-0}"
  local cur_mb
  cur_mb="$(zram_size_mb)"

  if [ "$want_mb" -eq 0 ]; then
    if [ "$cur_mb" -gt 0 ]; then
      want_mb="$cur_mb"
    else
      want_mb=4096
    fi
  fi

  # Raise-only: a smaller request never wins over what is already configured.
  if [ "$cur_mb" -ge "$want_mb" ] && [ "$cur_mb" -gt 0 ]; then
    echo "zram already ${cur_mb}MB, refusing to shrink to ${want_mb}MB" 1>&2
    want_mb="$cur_mb"
  fi

  local algorithm
  algorithm="$(best_zram_algorithm)"

  reset_all_zram

  # 获取 zram 序号
  local ram_dev
  if [ -e "/sys/class/zram-control/hot_add" ]; then
    ram_dev="$(cat /sys/class/zram-control/hot_add)"
  else
    ram_dev='0'
  fi

  local zram="zram${ram_dev}"
  local zram_dev="/dev/block/${zram}"

  swapoff "$zram_dev" > /dev/null 2>&1
  write "/sys/block/${zram}/comp_algorithm" "$algorithm"
  write "/sys/block/${zram}/reset" 1
  write "/sys/block/${zram}/disksize" "${want_mb}M"
  mkswap "$zram_dev" > /dev/null 2>&1
  swapon "$zram_dev" > /dev/null 2>&1

  enable_swap_props "$((want_mb * 1048576))"
}

# 禁用 zram
disable_zram() {
  reset_all_zram

  setprop vnswap.enabled false
  setprop ro.config.zram false
  setprop ro.config.zram.support false
  setprop zram.disksize 0
  write /proc/sys/vm/swappiness 0
}
