#!/system/bin/sh
state=$1
settings put global low_power $1;
settings put global low_power_sticky $1;

function killproc()
{
    stop "$1" 2> /dev/null
    killall -9 "$1" 2> /dev/null
}

# Whether or not app auto restriction is enabled. When it is enabled, settings app will  auto restrict the app if it has bad behavior(e.g. hold wakelock for long time).
# [app_auto_restriction_enabled]

#Whether or not to enable Forced App Standby on small battery devices.         * Type: int (0 for false, 1 for true)
# forced_app_standby_for_small_battery_enabled

# Feature flag to enable or disable the Forced App Standby feature.         * Type: int (0 for false, 1 for true)
# forced_app_standby_enabled

# Whether or not to enable the User Absent, Radios Off feature on small battery devices.         * Type: int (0 for false, 1 for true)
# user_absent_radios_off_for_small_battery_enabled

echo 'Power save mode may not work while charging'
echo '-'

if [[ $state = "1" ]]
then
    echo "Enable automatic app restriction (may require Android Pie)"
    settings put global app_auto_restriction_enabled true

    echo "Enable forced app standby"
    settings put global forced_app_standby_enabled 1

    echo "Enable app standby"
    settings put global app_standby_enabled 1

    echo "Enable forced app standby for small-battery devices"
    settings put global forced_app_standby_for_small_battery_enabled 1

    ai=`settings get system ai_preload_user_state`
    if [[ ! "$ai" = "null" ]]
    then
      echo "Disable MIUI 10 AI preloading"
      settings put system ai_preload_user_state 0
    fi

    echo "Enable native Android power save mode"
    settings put global low_power 1
    settings put global low_power_sticky 1

    echo "Stop debug services and logging processes"
    killproc cnss_diag
    killproc subsystem_ramdump
    killproc tcpdump
    # killproc logd
    # killproc adbd
    #stop thermal-engine 2> /dev/null
    #killall -9 magiskd 2> /dev/null
    if [[ -e /sys/zte_power_debug/switch ]]; then
        echo 0 > /sys/zte_power_debug/switch
    fi
    if [[ -e /sys/zte_power_debug/debug_enabled ]]; then
        echo N > /sys/kernel/debug/debug_enabled
    fi
    #killall -9 magiskd 2> /dev/null
    killall -9 magisklogd 2> /dev/null

    echo "Clearing background doze whitelist"
    echo "Please wait..."
    for item in `dumpsys deviceidle whitelist`
    do
        app=`echo "$item" | cut -f2 -d ','`
        #echo "deviceidle whitelist -$app"
        dumpsys deviceidle whitelist -$app 2>&1 >/dev/null
        am set-inactive $app true 2>&1 >/dev/null
        am set-idle $app true 2>&1 >/dev/null
        # Android 9.0: make background apps idle immediately
        am make-uid-idle --user current $app 2>&1 >/dev/null
    done
    for app in `pm list packages -3  | cut -f2 -d ':'`
    do
        am set-inactive $app true > /dev/null 2>&1
        am set-idle $app true > /dev/null 2>&1
        am make-uid-idle --user current $app > /dev/null 2>&1
    done
    dumpsys deviceidle step
    dumpsys deviceidle step
    dumpsys deviceidle step
    dumpsys deviceidle step

    echo 3 > /proc/sys/vm/drop_caches

    echo 'Note: after enabling power save mode, Scene may not stay in the background'
    echo 'Also, background message push may not be received!'
    echo ''
else
    echo "Disable automatic app restriction (may require Android Pie)"
    settings put global app_auto_restriction_enabled false
    settings reset global app_auto_restriction_enabled

    echo "Disable forced app standby"
    settings put global forced_app_standby_enabled 0
    settings reset global forced_app_standby_enabled

    echo "Enable app standby"
    settings put global app_standby_enabled 1
    settings reset global app_standby_enabled

    echo "Disable forced app standby for small-battery devices"
    settings put global forced_app_standby_for_small_battery_enabled 0
    settings reset global forced_app_standby_for_small_battery_enabled

    echo "Disable native Android power save mode"
    settings put global low_power 0
    settings put global low_power_sticky 0
    settings reset global low_power
    settings reset global low_power_sticky
fi

echo 'State switched; this may not work on some heavily customized systems!'
echo '-'


