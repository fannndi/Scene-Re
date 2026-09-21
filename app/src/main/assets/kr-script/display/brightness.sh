dir=""
for it in /sys/class/backlight/panel0-backlight \
/sys/devices/platform/panel_drv_0/backlight/panel; do
  if [[ -d $it ]]; then
    dir=$it
    break
  fi
done

supported() {
  if [[ "$dir" != '' ]]; then
    echo 1
  else
    echo 0
  fi
}

get_brightness() {
  cat $dir/brightness
}

set_brightness(){
  path=$dir/brightness
  chmod 664 $path
  echo $brightness > $path
  if [[ "$lock" == '1' ]]; then
    chmod 444 $path
  fi
}

get_max_brightness() {
  cat $dir/max_brightness
}

$1