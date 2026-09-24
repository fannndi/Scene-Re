source ./kr-script/common/mount.sh

dir=/system/usr/keylayout
file=$dir/gpio-keys.kl

# Prefer the overlay copy when one exists, otherwise read the live file.
full_path="$(write_target_for "$file" 2>/dev/null)"
if [[ -z "$full_path" ]] || [[ ! -f "$full_path" ]]; then
    full_path=$file
fi

grep '^key 689' $full_path | awk -F ' ' '{print $3}'
