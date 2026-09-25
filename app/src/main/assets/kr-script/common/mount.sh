#!/system/bin/sh

# Remount system partitions as read-write.
#
# Two partition layouts have to be handled:
#
#   classic  - /system, /vendor, /product are real partitions reachable through
#              /dev/block/bootdevice/by-name/*.
#   dynamic  - Android 10+ devices (MIUI 14 on surya included) keep them inside
#              the "super" partition as logical volumes, reachable only through
#              /dev/block/mapper/*. The by-name entries for system/vendor do not
#              exist there, so remounting by-name fails silently.
#
# In addition, dynamic partitions carry AVB/dm-verity (fstab flag avb=vbmeta_system),
# so even the mapper path stays read-only until verification is disabled on the
# boot image. mount_all() reports that case instead of pretending success.
#
# Returns 0 when at least one system partition became writable, 1 otherwise.
function mount_all() {
    local m part ok=1

    # 1) Plain remount by mount point: works on classic layouts and on dynamic
    #    layouts whose verity is already disabled.
    for m in / /system /system_ext /product /vendor /system/vendor; do
        [[ -d "$m" ]] || continue
        mount -o rw,remount "$m" 2> /dev/null && ok=0
        $BUSYBOX mount -o rw,remount "$m" 2> /dev/null && ok=0
    done

    # 2) Dynamic partitions: remount the logical volume onto its mount point.
    if [[ -d /dev/block/mapper ]]; then
        for part in system system_ext product vendor; do
            [[ -e "/dev/block/mapper/$part" ]] || continue
            [[ -d "/$part" ]] || continue
            mount -o rw,remount "/dev/block/mapper/$part" "/$part" 2> /dev/null && ok=0
            $BUSYBOX mount -o rw,remount "/dev/block/mapper/$part" "/$part" 2> /dev/null && ok=0
        done
    fi

    # 3) Classic layouts: fall back to the by-name block device, but only when
    #    the entry actually exists. On dynamic partitions it does not.
    for part in system vendor; do
        [[ -e "/dev/block/bootdevice/by-name/$part" ]] || continue
        [[ -d "/$part" ]] || continue
        mount -o remount,rw "/dev/block/bootdevice/by-name/$part" "/$part" 2> /dev/null && ok=0
        $BUSYBOX mount -o remount,rw "/dev/block/bootdevice/by-name/$part" "/$part" 2> /dev/null && ok=0
    done

    # 4) Verify with a real write. mount(8) reporting "rw" is not proof on
    #    dm-verity devices: the filesystem accepts the remount flag and still
    #    refuses the write.
    if [[ -d /system ]]; then
        if touch /system/.scene_rw_probe 2> /dev/null; then
            rm -f /system/.scene_rw_probe 2> /dev/null
            ok=0
        elif [[ "$ok" = "0" ]]; then
            # Remount claimed success but the partition is still read-only:
            # dm-verity / AVB is enforcing. Surface it instead of failing quietly.
            echo "scene: /system tetap read-only (dm-verity/AVB aktif) - " \
                 "nonaktifkan verifikasi pada vbmeta untuk menulis partisi" 1>&2
            ok=1
        fi
    fi

    return $ok
}

# ---------------------------------------------------------------------------
# Write-target resolution
#
# Systemless writes go through whichever backend is available:
#
#   overlay  - $OVERLAY_PATH is set by the app when an overlay directory
#              exists. The file is mirrored into it and swapped in at boot.
#              Non-destructive. $MAGISK_PATH is a legacy alias for the same
#              value; prefer $OVERLAY_PATH.
#   direct   - no overlay, so the real file on the partition is edited after a
#              remount. This is what makes the feature work with plain root.
#
# Callers should not test the overlay path directly. Use write_target_for(),
# which returns the path to edit for either backend, and fails when neither is
# usable.
# ---------------------------------------------------------------------------

# Echo the overlay directory (no trailing slash), or nothing when there is none.
function root_overlay_dir() {
    local dir="${OVERLAY_PATH:-$MAGISK_PATH}"
    if [[ -n "$dir" ]]; then
        echo "${dir%/}"
    fi
}

# Echo the path that should be edited for a given absolute system path.
# $1 absolute system path, e.g. /system/usr/keylayout/gpio-keys.kl
# Returns 0 and prints the target, or returns 1 and prints nothing.
function write_target_for() {
    local system_path="$1"
    if [[ -z "$system_path" ]]; then
        return 1
    fi

    local overlay="$(root_overlay_dir)"
    if [[ -n "$overlay" ]] && [[ -d "$overlay" ]]; then
        # Overlay backend: mirror /vendor and /product under a system/ prefix,
        # matching the layout the boot overlay expects.
        case "$system_path" in
            /vendor/*|/product/*) echo "${overlay}/system${system_path}" ;;
            *)                    echo "${overlay}${system_path}" ;;
        esac
        return 0
    fi

    # Direct backend: the live path is the target, provided it is writable.
    if [[ "$ROOT_BACKEND" == "direct" ]] || [[ "$ROOT_BACKEND" == "" ]]; then
        mount_all > /dev/null 2>&1
        echo "$system_path"
        return 0
    fi

    return 1
}

# True (0) when any write backend is usable.
function write_backend_available() {
    local overlay="$(root_overlay_dir)"
    if [[ -n "$overlay" ]] && [[ -d "$overlay" ]]; then
        return 0
    fi
    [[ "$ROOT_BACKEND" == "direct" ]] && return 0
    return 1
}

# Create the parent directory of a write target, mounting first when the target
# lives on the real partition.
# $1 the target path returned by write_target_for
function prepare_target_dir() {
    local target="$1"
    local dir
    dir="$(dirname "$target")"
    if [[ -n "$(root_overlay_dir)" ]] && [[ "$target" == "$(root_overlay_dir)"* ]]; then
        mkdir -p "$dir"
    else
        mount_all > /dev/null 2>&1
        mkdir -p "$dir" 2> /dev/null
    fi
}

# Snapshot the pristine file once, for targets on the real partition.
# Repeated toggles never overwrite the original backup.
# $1 the target path returned by write_target_for
function snapshot_target() {
    local target="$1"
    # Only meaningful for real files, not overlay copies.
    if [[ -n "$(root_overlay_dir)" ]] && [[ "$target" == "$(root_overlay_dir)"* ]]; then
        return 0
    fi
    if [[ -f "$target" ]] && [[ ! -f "$target.scene.bak" ]]; then
        cp -p "$target" "$target.scene.bak"
    fi
}

# Restore a target from its snapshot, if one was taken.
# $1 the target path
# Returns 0 when a restore happened.
function restore_target() {
    local target="$1"
    if [[ -f "$target.scene.bak" ]]; then
        mount_all > /dev/null 2>&1
        cp -p "$target.scene.bak" "$target"
        sync
        return 0
    fi
    return 1
}
