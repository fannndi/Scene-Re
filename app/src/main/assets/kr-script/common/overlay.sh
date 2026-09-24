#!/system/bin/sh

# Overlay / systemless replacement helpers.
#
# Two write strategies exist and the app tells us which is active via
# $ROOT_BACKEND (overlay | direct | none):
#
#   overlay - $OVERLAY_PATH holds a directory that boot re-mounts over /system.
#             Files are mirrored there and swapped in on the next boot.
#   direct  - no overlay, so the real file on the partition is edited in place
#             after a remount, keeping a one-time .scene.bak snapshot.
#
# $OVERLAY_PATH is empty when the direct backend is active. The legacy name
# $MAGISK_PATH carries the same value; prefer $OVERLAY_PATH in new code.

# Return 1 when an overlay directory is available, 0 otherwise.
# NOTE: this follows the historical (inverted) convention of this file.
function overlay_available() {
    local dir="$(overlay_dir)"
    if [[ -n "$dir" ]] && [[ -d "$dir" ]]
    then
        return 1
    else
        return 0
    fi
}

# Echo the overlay directory with no trailing slash, or nothing when there is none.
function overlay_dir() {
    local dir="${OVERLAY_PATH:-$MAGISK_PATH}"
    if [[ -n "$dir" ]]; then
        echo "${dir%/}"
    fi
}

# Replace a file, usage:
# overlay_file_replace "./kr-script/miui/resources/com.android.systemui" "/system/media/theme/default/com.android.systemui"
function overlay_file_replace() {
    local input="$1"
    local target="$2"
    local output="$(overlay_dir)$target"
    if [[ -f "$input" ]]
    then
        local dir="$(dirname $output)"
        mkdir -p "$dir"
        cp "$input" "$output"
        chmod 755 "$output"
        overlay_file_matches "$input" "$target"
        local result="$?"
        return $result
    else
        echo "$input does not exist; cannot copy to overlay" 1>&2
        return 0
    fi
}

# Cancel a file replacement.
function overlay_file_restore()
{
    local output="$(overlay_dir)$1"
    if [[ -e "$output" ]]
    then
        rm -rf "$output"
    fi
    return 1
}

# Check whether a file is replaced by the overlay.
function overlay_file_exists()
{
    if [[ -f "$(overlay_dir)$1" ]]
    then
        return 1
    else
        return 0
    fi
}

# Check whether a file matches the overlay copy.
function overlay_file_matches()
{
    local input="$1"
    local output="$(overlay_dir)$2"
    if [[ -f "$input" ]] && [[ -f "$output" ]]
    then
        local md5=`busybox md5sum $input | cut -f1 -d ' '`
        local verify=`busybox md5sum $output | cut -f1 -d ' '`
        if [[ "$md5" = "$verify" ]]
        then
            return 1
        else
            return 0
        fi
    else
        return 0
    fi
}

# Persist a system property override and apply it to the running system.
#
# The value is pushed live via resetprop (which can replace read-only
# properties), falling back to setprop, so the change is visible without
# rebooting; it is also recorded so it survives the next boot.
function set_system_prop_override() {
    local root="$(overlay_dir)"
    if [[ -n "$root" ]] && [[ -d "$root" ]];
    then
        touch "$root/system.prop"
        $BUSYBOX sed -i "/^$1=/d" "$root/system.prop"
        $BUSYBOX echo "$1=$2" >> "$root/system.prop"
        apply_prop_live "$1" "$2"
        return 1
    fi;
    return 0
}

# Apply a property to the running system without rebooting.
function apply_prop_live() {
    if command -v resetprop > /dev/null 2>&1
    then
        resetprop -n "$1" "$2" 2> /dev/null
    else
        setprop "$1" "$2" 2> /dev/null
    fi
}

# Drop a persisted property override from the overlay.
function cancel_system_prop_override()
{
    local root="$(overlay_dir)"
    if [[ -n "$root" ]] && [[ -d "$root" ]];
    then
        $BUSYBOX sed -i "/^$1=/d" "$root/system.prop"
        apply_prop_live "$1" "$2"
        return 1
    fi;
    return 0
}
