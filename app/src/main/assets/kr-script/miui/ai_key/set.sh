source ./kr-script/common/mount.sh

dir=/system/usr/keylayout
file=$dir/gpio-keys.kl

target="$(write_target_for "$file")"
if [[ -z "$target" ]]; then
    echo 'No write backend available; cannot apply changes.' 1>&2
    exit 1
fi

if [[ "$state" != "" ]] && [[ "$state" != "AI" ]]; then
    if [[ ! -f "$target" ]]; then
        prepare_target_dir "$target"
        cp $file "$target"
    fi
    snapshot_target "$target"

    busybox sed -i "s/^key 689.*/key 689   $state/" "$target"
    echo $state
    if [[ -n "$(root_overlay_dir)" ]] && [[ "$target" == "$(root_overlay_dir)"* ]]; then
        echo 'This change requires a reboot to take effect!' 1>&2
    fi
else
    # Reset to stock: drop the override if it came from the overlay, otherwise
    # restore the snapshot taken before the first modification.
    if [[ -n "$(root_overlay_dir)" ]] && [[ "$target" == "$(root_overlay_dir)"* ]]; then
        if [[ -f "$target" ]]; then
            rm "$target"
            echo 'This change requires a reboot to take effect!' 1>&2
        fi
    else
        if restore_target "$target"; then
            echo 'Reverted to the original file.'
        fi
    fi
fi
