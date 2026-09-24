#!/system/bin/sh
# Page visibility gate: echo 1 when any write backend is usable, 0 otherwise.
#
# The app exports ROOT_BACKEND as the lowercased Backend enum name: overlay | direct | none.
# OVERLAY_PATH carries the overlay directory (empty when none). Either backend is
# sufficient for these features, so we must not require an overlay to be present.
#
# Usage in a page XML:
#   visible="run common/backend_available.sh"

if [[ -n "$OVERLAY_PATH" ]] && [[ -d "$OVERLAY_PATH" ]]; then
    echo 1
    exit 0
fi

if [[ "$ROOT_BACKEND" == "overlay" ]] || [[ "$ROOT_BACKEND" == "direct" ]]; then
    echo 1
    exit 0
fi

# Fall back to a live writability probe when the environment is missing entirely,
# e.g. when the script is run from an adb shell rather than through the app.
if [[ -z "$ROOT_BACKEND" ]]; then
    if [[ -w /system ]] || [[ -w /system/build.prop ]]; then
        echo 1
        exit 0
    fi
fi

echo 0
