#!/system/bin/sh

# Shared helpers for the Scene profile option scripts
# (scene_profile_options.sh and scene_qualcomm_boost.sh).
#
# Sourced through "$(dirname "$0")", so both scripts must be extracted into
# the same directory (FileWrite.writePrivateShellFile does that). Keep the
# functions here free of Scene-specific state: pure read/write helpers only.

write_val() {
    local node="$1"
    local value="$2"
    if [[ -e "$node" ]]; then
        chmod 0664 "$node" 2> /dev/null
        echo "$value" > "$node" 2> /dev/null
    fi
}

read_val() {
    if [[ -e "$1" ]]; then
        cat "$1" 2> /dev/null
    fi
}

# $1 = space separated list, $2 = target; echoes the closest entry
nearest_freq() {
    local best=""
    local bestdiff=""
    local f diff
    for f in $1; do
        diff=$(( f > $2 ? f - $2 : $2 - f ))
        if [[ -z "$best" ]] || [[ $diff -lt $bestdiff ]]; then
            best=$f
            bestdiff=$diff
        fi
    done
    echo "$best"
}

# $1 = frequency list file; echoes the highest OPP
max_freq() {
    tr ' ' '\n' < "$1" 2> /dev/null | grep -v '^[[:space:]]*$' | sort -n | tail -n 1
}

# $1 = frequency list file; echoes the lowest OPP
min_freq() {
    tr ' ' '\n' < "$1" 2> /dev/null | grep -v '^[[:space:]]*$' | sort -n | head -n 1
}

# $1 = frequency list file; echoes the middle OPP (Encore which_midfreq)
mid_freq() {
    local total mid
    total="$(tr ' ' '\n' < "$1" 2> /dev/null | grep -v '^[[:space:]]*$' | wc -l)"
    [[ "$total" -eq 0 ]] && return 0
    mid=$(( (total + 1) / 2 ))
    tr ' ' '\n' < "$1" 2> /dev/null | grep -v '^[[:space:]]*$' | sort -nr | head -n "$mid" | tail -n 1
}

snapshot_tunable() {
    # $1 = key, $2 = node; the first value seen is kept in a prop so the
    # tunable can be restored when the option is turned off.
    local prop="vtools.scene.tweak.bak.$1"
    local current
    [[ "$(getprop $prop)" != "" ]] && return 0
    current="$(read_val "$2")"
    [[ -n "$current" ]] && setprop $prop "$current"
}

apply_tunable() {
    # $1 = key, $2 = node, $3 = value
    [[ -e "$2" ]] || return 0
    snapshot_tunable "$1" "$2"
    write_val "$2" "$3"
}

restore_tunable() {
    local value
    value="$(getprop "vtools.scene.tweak.bak.$1")"
    [[ -z "$value" ]] && return 0
    write_val "$2" "$value"
}
