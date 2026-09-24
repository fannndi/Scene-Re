source ./kr-script/common/mount.sh

dir=/system/usr/keylayout
file=$dir/gpio-keys.kl

# Prefer the overlay copy when one exists, otherwise read the live file.
full_path="$(write_target_for "$file" 2>/dev/null)"
if [[ -z "$full_path" ]] || [[ ! -f "$full_path" ]]; then
    full_path=$file
fi

key_code=`grep '^key 377' $full_path | awk -F ' ' '{print $3}'`

if [[ "$key_code" != "" ]];then
    key_name=`grep "$key_code" $PAGE_WORK_DIR/377_key/options.txt | awk -F '|' '{print $2}'`
    if [[ "$key_name" == "" ]]; then
        echo $key_code
    else
        echo $key_name
    fi
fi
