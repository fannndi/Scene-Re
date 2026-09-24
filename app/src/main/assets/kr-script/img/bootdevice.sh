dirs="/dev/block/bootdevice/by-name
/dev/block/by-name
/dev/block/platform/bootdevice/by-name"
root_dir=
for dir in $dirs
do
  if [[ -d $dir ]];then
    # echo "Found dir:" $dir
    root_dir=$dir
  fi
done

# Called with `verify` from more.xml to decide whether the "Boot image tools"
# entry is shown. Without this branch the page reported success unconditionally,
# because a script that prints nothing is treated as "no error".
# Only advertise the page when a boot partition is actually addressable.
if [[ "$1" == 'verify' ]]; then
  if [[ -n "$root_dir" && -e "$root_dir/boot" ]]; then
    echo '1'
  fi
fi
