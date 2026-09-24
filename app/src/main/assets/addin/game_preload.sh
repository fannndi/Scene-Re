#!/system/bin/sh
# Game preload: touch the readable files of an app so the page cache is warm
# before the heavy work starts. $1 = package name, $2 = per-file budget in MB.
#
# This is the shell equivalent of vmtouch -t (readahead into the page cache).
# Only files up to the budget are read, so a large split APK cannot stall the
# preload pass; the budget applies per file, not to the total.

pkg="$1"
budget_mb="$2"
[[ -z "$budget_mb" ]] && budget_mb=500
budget=$(( budget_mb * 1024 * 1024 ))

[[ -z "$pkg" ]] && exit 0

base="$(pm path "$pkg" 2> /dev/null | head -n 1 | cut -d: -f2)"
[[ -z "$base" ]] && exit 0
dir="$(dirname "$base")"

target=""
for candidate in "$dir/lib/arm64" "$dir/lib/arm" "$dir"; do
    if [[ -d "$candidate" ]]; then
        target="$candidate"
        break
    fi
done
[[ -z "$target" ]] && exit 0

touched=0
for file in $(find "$target" -type f 2> /dev/null); do
    size="$(stat -c %s "$file" 2> /dev/null)"
    if [[ -n "$size" ]] && [[ "$size" -le "$budget" ]]; then
        cat "$file" > /dev/null 2>&1
        touched=$(( touched + 1 ))
    fi
done
echo "preloaded $touched files from $target"
exit 0
