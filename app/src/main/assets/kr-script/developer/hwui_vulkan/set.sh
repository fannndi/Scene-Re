#!/system/bin/sh

source ./kr-script/common/props.sh
if [[ $state = 1 ]]
then
    value='true'
else
    value='false'
fi

prop="ro.hwui.use_vulkan"

set_system_prop_override $prop $value
if [[ "$?" = "1" ]];
then
    echo "Changed $prop via the overlay. A reboot is required to take effect."
else
    set_system_prop $prop $value
fi

