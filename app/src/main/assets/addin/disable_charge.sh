#!/system/bin/sh

# Stop the battery from charging while the plug stays connected (bypass
# charging). Every node is optional; the first family the kernel exposes does
# the job. Node set merged with the Qualcomm / Xiaomi table used by
# BypassCharge.kt so both paths cover the same devices.

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

worked=0

if [[ -f $bce ]] || [[ -f $bce2 ]] || [[ -f $suspend ]] || [[ -f $suspend2 ]] || [[ -f $dis ]] || [[ -f $restricted ]] || [[ -f $qpnp ]] || [[ -f $qpnp_suspend ]] || [[ -f $mca ]]; then
  set_value $bce 0
  set_value $bce2 0
  set_value $dis 1
  set_value $suspend 1
  set_value $suspend2 1
  set_value $restricted 1
  set_value $qpnp 1
  set_value $qpnp_suspend 1
  set_value $mca 1
  setprop vtools.bp 1
  worked=1
fi

if [[ "$worked" = "0" ]] && [[ -f $max ]]; then
  current_max_path="/sys/class/power_supply/battery/constant_charge_current_max"
  current_max_backup="vtools.charge.current.max"
  current_max=`getprop $current_max_backup`
  if [[ "$current_max" == "" ]] && [[ -f $current_max_path ]]; then
    setprop $current_max_backup `cat $current_max_path`
  fi

  set_value $max 0
  setprop vtools.bp 1
  worked=1
fi

if [[ "$worked" = "0" ]]; then
  echo 'error'
fi
