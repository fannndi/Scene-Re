source ./kr-script/common/mount.sh

file="/system/etc/hosts"

target="$(write_target_for "$file")"
if [[ -z "$target" ]]; then
    echo 'No write backend available; cannot apply changes.' 1>&2
    exit 1
fi

# Seed the target from the live file on first use.
if [[ ! -f "$target" ]]; then
    prepare_target_dir "$target"
    cp $file "$target"
fi
snapshot_target "$target"

if [[ $state == 1 ]]; then
    # Restore updates: remove the block rule
    $BUSYBOX sed -i '/127.0.0.1[[:space:]]\+update\.miui\.com/d' "$target"
    echo 'Removed the update block rule.'
else
    # Block updates: add the rule
    $BUSYBOX sed -i '$a127.0.0.1        update.miui.com' "$target"
    echo 'Added "127.0.0.1        update.miui.com" to hosts'
fi

pm clear com.android.updater 2> /dev/null

if [[ -n "$(root_overlay_dir)" ]] && [[ "$target" == "$(root_overlay_dir)"* ]]; then
    echo 'This action requires a reboot to take effect!'
else
    echo 'A reboot may be required for changes to take effect!'
fi
