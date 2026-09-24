#!/system/bin/sh

# Undo disable_charge.sh: release every bypass node the device may expose.

set_value() {
  if [[ -f "$1" ]];
  then
    chmod 0666 "$1"
    echo "$2" > "$1"
  fi
}

max='/sys/class/power_supply/battery/constant_charge_current_max'
bce='/sys/class/power_supply/battery/battery_charging_enabled'
bce2='/sys/class/power_supply/battery/charging_enabled'
dis='/sys/class/power_supply/battery/charge_disable'
suspend='/sys/class/power_supply/battery/input_suspend'
suspend2='/sys/class/qcom-battery/input_suspend'
restricted='/sys/class/qcom-battery/restricted_charging'
qpnp='/sys/class/power_supply/qpnp_adaptive_charge/blocking'
qpnp_suspend='/sys/class/power_supply/qpnp_adaptive_charge/input_suspend'
mca='/sys/class/power_supply/mca_charge_interface/input_suspend'

if [[ -f $max ]] && [[ `cat $max` = "0" ]]; then
  set_value $max 3000000
  current_max_path=$max
  # "/sys/class/power_supply/battery/constant_charge_current_max"
  current_max_backup="vtools.charge.current.max"
  current_max=`getprop $current_max_backup`
  if [[ ! "$current_max" == "" ]] ; then
    set_value $current_max_path $current_max
  fi
fi

if [[ -f $bce ]] || [[ -f $bce2 ]] || [[ -f $suspend ]] || [[ -f $suspend2 ]] || [[ -f $dis ]] || [[ -f $restricted ]] || [[ -f $qpnp ]] || [[ -f $qpnp_suspend ]] || [[ -f $mca ]]; then
  set_value $bce 1
  set_value $bce2 1
  set_value $dis 0
  set_value $suspend 0
  set_value $suspend2 0
  set_value $restricted 0
  set_value $qpnp 0
  set_value $qpnp_suspend 0
  set_value $mca 0
  setprop vtools.bp 0
else
  echo 'error'
fi
