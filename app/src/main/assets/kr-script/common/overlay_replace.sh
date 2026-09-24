#!/system/bin/sh

source ./kr-script/common/overlay.sh
source ./kr-script/common/mount.sh

# Replace a system file.
#
# Two strategies, chosen automatically:
#   1. overlay  - write into the overlay directory (a root manager module dir when
#                 one exists). Non-destructive, survives OTA, applied on next boot.
#                 Preferred when available.
#   2. direct   - remount the partition read/write and overwrite the live file,
#                 keeping a one-time .scene.bak. Used when no overlay exists,
#                 which is the "root without any module framework" case.
function _replace_file() {
    local resource="$1"
    local output="$2"
    overlay_available
    mg="$?"

    if [[ "$mg" = 1 ]]
    then
        echo "Replace via overlay: $output"
        overlay_file_replace $resource $output
        success="$?"
        if [[ "$success" = 1 ]]; then
            echo 'Operation successful. Please reboot the phone!'
        else
            echo 'Operation failed...' 1>&2
        fi
    else
        echo "Replace $output directly"
        mount_all

        # Back up the pristine file once, so repeated toggles never clobber it.
        if [[ -f "$output" ]] && [[ ! -f "$output.scene.bak" ]]; then
            cp -p "$output" "$output.scene.bak"
        fi

        cp -pdrf $resource $output
        chmod 0755 $output
        sync
    fi
}

# Restore a system file to its pre-replacement state.
#
# Fixed: the original direct-mode branch copied the backup back and then
# immediately deleted the target with `rm -f`, discarding the restore it had
# just performed. The restore now preserves the file.
function _restore_file() {
    local resource="$1"
    local output="$2"

    overlay_available
    mg="$?"

    if [[ "$mg" = 1 ]]
    then
        # Overlay mode: drop the redirect from the overlay.
        overlay_file_exists "$output"
        local file_in_overlay="$?"

        if [[ "$file_in_overlay" = "1" ]]; then
            echo "Remove from overlay: $output"
            overlay_file_restore $output
            echo 'Please reboot the phone for changes to take effect!'
        else
            echo "Restore $output"
            mount_all
            if [[ -f "$output.scene.bak" ]]
            then
                cp -p "$output.scene.bak" "$output"
                sync
            fi
        fi
    else
        # Direct mode: put the backup back.
        echo "Restore $output"
        mount_all
        if [[ -f "$output.scene.bak" ]]
        then
            cp -p "$output.scene.bak" "$output"
            sync
        fi
    fi
}

# Replace or restore a file, choosing the backend automatically.
# mixture_hook_file "./kr-script/miui/resources/com.android.systemui" "/system/media/theme/default/com.android.systemui" "$mode"
# $mode can be 1 or 0: 1 = replace, 0 = cancel replace
function mixture_hook_file()
{
    local resource="$1"
    local output="$2"
    local mode="$3"

    if [[ $mode = '1' ]]
    then
        _replace_file "$resource" "$output"
    else
        _restore_file "$resource" "$output"
    fi
}

# Whether the file currently matches the expected resource, via either backend.
# file_mixture_hooked "./kr-script/miui/resources/com.android.systemui" "/system/media/theme/default/com.android.systemui"
# @return 1 or 0
function file_mixture_hooked()
{
    local resource="$1"
    local output="$2"

    # Check whether the resource file exists
    if [[ ! -f $resource ]]
    then
        return 0
    fi

    # Overlay backend: is the redirect present and byte-identical?
    overlay_file_exists $output
    exist="$?"

    overlay_file_matches $resource $output
    equals="$?"

    if [[ $exist = 1 ]] && [[ $equals = 1 ]]
    then
        return 1
    fi

    # Direct backend: compare against the live file.
    if [[ -f $output ]]
    then
        local md5=`busybox md5sum $resource | cut -f1 -d ' '`
        local verify=`busybox md5sum $output | cut -f1 -d ' '`

        if [[ "$md5" = "$verify" ]]
        then
            return 1
        fi
    fi

    return 0
}
