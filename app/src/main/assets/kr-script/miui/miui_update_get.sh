source ./kr-script/common/mount.sh

file="/system/etc/hosts"

# Prefer the overlay copy when one exists, otherwise read the live file.
full_path="$(write_target_for "$file" 2>/dev/null)"
if [[ -z "$full_path" ]] || [[ ! -f "$full_path" ]]; then
    full_path=$file
fi

if [[ -n `cat $full_path | grep "update.miui.com" | grep "127.0.0.1"` ]]; then
    echo 0;
else
    echo 1;
fi;