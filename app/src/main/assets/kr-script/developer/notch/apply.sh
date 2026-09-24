source ./kr-script/common/mount.sh

# Display-cutout overlays are RRO packages. They can be delivered either by
# mirroring them into the module's product/overlay directory, or, with no
# overlay backend, by writing them to the live product partition.
if ! write_backend_available; then
  echo 'This feature requires a writable root backend.' 1>&2
  return
fi

target_dir="$(write_target_for /product/overlay)"
if [[ -z "$target_dir" ]]; then
  echo 'Could not resolve the overlay target directory.' 1>&2
  return
fi

os=$(getprop ro.build.version.sdk)
sdk=sdk$os
dir=$PAGE_WORK_DIR/notch

echo 'Searching for resource folder...'
if [[ -d $dir/$sdk ]]; then
  echo 'Creating directory...'
  prepare_target_dir "$target_dir/placeholder"
  echo 'Copying overlay files...'
  for item in $dir/$sdk/*
  do
    echo '  ' $item
    cp -rf $item "$target_dir/"
  done
  if [[ "$type" == "hole" ]]; then
    echo 'Now, please reboot the phone first.'
    echo 'Then go to Settings > Developer options > Display cutout and choose "Hole-punch".'
  fi
else
  echo 'No overlay files found for your current device' 1>&2
fi
