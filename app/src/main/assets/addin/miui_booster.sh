#!/system/bin/sh

# MIUI booster service (MiuiBooster) authorization helper.
#
# The service behind the binder name "miuiboosterservice" gates every request on
# a UID allow-list instead of a signature permission: /system/xbin/miuibooster
# reads persist.sys.mibridge_auth_uids and only answers the UIDs listed there
# (persist.sys.mispeed_auth_uids is the equivalent list for the MiSpeed path).
# Adding Scene's own UID is therefore the entire setup - no module, no
# signature, no patch.
#
# Every change is reversible. The original value is snapshotted into
# vtools.scene.mibridge.bak before the first edit, and `revoke` restores it
# exactly. The snapshot lives in a non-persistent property, so it is cleared by
# a reboot - which is fine, because the allow-list itself is persistent and
# `revoke` can also fall back to a plain removal.
#
# usage:
#   sh miui_booster.sh supported
#   sh miui_booster.sh authorize <uid>
#   sh miui_booster.sh revoke <uid>
#   sh miui_booster.sh status <uid>

AUTH_PROP="persist.sys.mibridge_auth_uids"
MISPEED_PROP="persist.sys.mispeed_auth_uids"
ENABLE_PROP="persist.sys.enable_miui_booster"
BAK_PROP="vtools.scene.mibridge.bak"
JAR="/system/framework/MiuiBooster.jar"

# Print "1" when the service can be used at all, "0" otherwise.
miui_booster_supported() {
  if [[ ! -e "$JAR" ]]; then
    echo 0
    return
  fi
  if [[ "$(getprop $ENABLE_PROP)" != "true" ]]; then
    # The property may be absent on builds that enable the service by default;
    # the socket check below is the tie-breaker.
    if [[ ! -e /dev/socket/miui_booster ]]; then
      echo 0
      return
    fi
  fi
  echo 1
}

# Print "1" when the UID is already authorized.
miui_booster_authorized() {
  local uid="$1" cur
  [[ -n "$uid" ]] || { echo 0; return; }
  cur="$(getprop $AUTH_PROP)"
  case ",$cur," in
    *",$uid,"*) echo 1 ;;
    *) echo 0 ;;
  esac
}

# Add the UID to the allow-list. Idempotent; snapshots the original value once.
miui_booster_authorize() {
  local uid="$1" cur
  [[ -n "$uid" ]] || return 1
  [[ "$(miui_booster_supported)" = "1" ]] || return 1
  if [[ "$(miui_booster_authorized "$uid")" = "1" ]]; then
    return 0
  fi
  cur="$(getprop $AUTH_PROP)"
  if [[ -z "$(getprop $BAK_PROP)" ]]; then
    setprop "$BAK_PROP" "$cur"
  fi
  if [[ -n "$cur" ]]; then
    setprop "$AUTH_PROP" "$cur,$uid"
  else
    setprop "$AUTH_PROP" "$uid"
  fi
  return 0
}

# Remove the UID again and, when the snapshot is still available, restore the
# exact original list.
miui_booster_revoke() {
  local uid="$1" cur bak filtered
  [[ -n "$uid" ]] || return 1
  cur="$(getprop $AUTH_PROP)"
  bak="$(getprop $BAK_PROP)"
  filtered="$(echo "$cur" | tr ',' '\n' | grep -v "^${uid}$" | grep -v '^$' | paste -sd, -)"
  if [[ -n "$bak" ]]; then
    setprop "$AUTH_PROP" "$bak"
    setprop "$BAK_PROP" ""
  else
    setprop "$AUTH_PROP" "$filtered"
  fi
  return 0
}

case "$1" in
  supported)
    miui_booster_supported
    ;;
  authorized)
    miui_booster_authorized "$2"
    ;;
  authorize)
    miui_booster_authorize "$2"
    ;;
  revoke)
    miui_booster_revoke "$2"
    ;;
  *)
    echo "usage: $0 {supported|authorized <uid>|authorize <uid>|revoke <uid>}" 1>&2
    exit 2
    ;;
esac
