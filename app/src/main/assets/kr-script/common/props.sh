#!/system/bin/sh
source ./kr-script/common/mount.sh

# Read whether the prop value is 1 from build.prop
cat_prop_is_1()
{
    prop="$1"
    g="^$prop="
    # Reset so a miss in one source cannot leak a stale value into the next check.
    status=""
    if [[ -n "$(root_overlay_dir)" ]] && [[ -f "$(root_overlay_dir)/system.prop" ]]
    then
        status=`grep "$g" "$(root_overlay_dir)/system.prop" | cut -d '=' -f2`
    fi
    if [ "$status" = "1" ] || [ "$status" = "true" ]; then
        echo 1;
        exit 0
    elif [ "$status" = "0" ] || [ "$status" = "false" ]; then
        echo 0;
        exit 0
    fi

    status=""
    status=`grep "$g" /system/build.prop 2>/dev/null | cut -d '=' -f2`
    if [ "$status" = "1" ] || [ "$status" = "true" ]; then
        echo 1;
        exit 0
    fi


    if [[ -f "/vendor/build.prop" ]]
    then
        status=`grep "$g" /vendor/build.prop | cut -d '=' -f2`
    fi
    if [ "$status" = "1" ] || [ "$status" = "true" ]; then
        echo 1;
        exit 0
    fi

    echo 0
}

# Read whether the prop value is 0 from build.prop
cat_prop_is_0()
{
    prop="$1"
    g="^$prop="
    status=""
    if [[ -n "$(root_overlay_dir)" ]] && [[ -f "$(root_overlay_dir)/system.prop" ]]
    then
        status=`grep "$g" "$(root_overlay_dir)/system.prop" | cut -d '=' -f2`
    fi
    if [ "$status" = "0" ] || [ "$status" = "false" ]; then
        echo 1;
        exit 0
    elif [ "$status" = "1" ] || [ "$status" = "true" ]; then
        echo 0;
        exit 0
    fi

    status=""
    status=`grep "$g" /system/build.prop 2>/dev/null | cut -d '=' -f2`
    if [ "$status" = "0" ] || [ "$status" = "false" ]; then
        echo 1;
        exit 0
    fi

    status=""
    if [[ -f "/vendor/build.prop" ]]
    then
        status=`grep "$g" /vendor/build.prop | cut -d '=' -f2`
    fi
    if [ "$status" = "0" ] || [ "$status" = "false" ]; then
        echo 1;
        exit 0
    fi

    echo 0
}

# Persist a system property override and apply it to the running system.
#
# Previously the value was only appended to system.prop, so it did not take
# effect until the next reboot. It is now also pushed live via resetprop
# (which can replace read-only properties), falling back to setprop.
set_system_prop_override() {
    local dir="$(root_overlay_dir)"
    if [[ -n "$dir" ]];
    then
        echo "Overlay detected; this change will be applied through the overlay"
        touch "$dir/system.prop"
        $BUSYBOX sed -i "/^$1=/d" "$dir/system.prop"
        $BUSYBOX echo "$1=$2" >> "$dir/system.prop"
        apply_prop_live "$1" "$2"
        return 1
    fi;
    return 0
}

# Write a property into build.prop on the live partition.
#
# This is the no-overlay path. It remounts, snapshots the pristine file once,
# rewrites it, and applies the value live so it takes effect immediately.
set_system_prop() {
    local prop=$1
    local state=$2

    local path="/system/build.prop"
    if [[ -f /vendor/build.prop ]] && [[ -n `cat /vendor/build.prop | grep "^$prop="` ]]
    then
        local path="/vendor/build.prop"
    fi

    echo 'Writing directly to the system partition. This requires an unlocked bootloader.'
    echo "Target: $path"

    echo 'Step1. Mount the partition as read-write'
    mount_all

    # Snapshot the pristine file once, before the first modification.
    if [[ -f "$path" ]] && [[ ! -f "$path.scene.bak" ]]; then
        cp -p "$path" "$path.scene.bak"
    fi

    local tmp="$path.scene.tmp"
    $BUSYBOX sed "/^$prop=/d" "$path" > "$tmp"
    $BUSYBOX echo "$prop=$state" >> "$tmp"
    echo "Step2. Update $prop=$state"

    echo 'Step3. Write file'
    cat "$tmp" > "$path"
    rm -f "$tmp"
    chmod 0755 "$path"
    sync

    echo 'Step4. Apply to the running system'
    apply_prop_live "$prop" "$state"

    echo ''
    echo 'Applied. Takes full effect after reboot.'
}

# Restore a property that was written by set_system_prop.
restore_system_prop() {
    local prop=$1
    local path="/system/build.prop"
    if [[ -f /vendor/build.prop.scene.bak ]]; then
        path="/vendor/build.prop"
    fi

    if [[ ! -f "$path.scene.bak" ]]
    then
        echo "No backup found for $path; nothing to restore." 1>&2
        return 1
    fi

    echo "Restoring $path from backup"
    mount_all
    cat "$path.scene.bak" > "$path"
    chmod 0755 "$path"
    sync
    echo 'Restored. Takes full effect after reboot.'
}
