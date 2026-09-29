#!/usr/bin/env bash
# Tap an element on a mapped screen (see tools/ui-map.sh).
#
#   bash tools/agent-tap.sh tuner "Balance"
#   bash tools/agent-tap.sh tweaks id:direct
#
# Selector: plain text matches text/desc (case-insensitive substring),
# "id:<part>" matches the resource id. Taps the first enabled match.
set -u
cd "$(dirname "$0")/.."

ADB="${ADB:-}"
if [ -z "$ADB" ]; then
  if command -v adb >/dev/null 2>&1; then ADB=adb
  elif [ -x "$HOME/Android/Sdk/platform-tools/adb" ]; then ADB="$HOME/Android/Sdk/platform-tools/adb"
  else echo "adb not found"; exit 1; fi
fi

SCREEN="${1:?usage: agent-tap.sh <screen> <selector>}"
SEL="${2:?usage: agent-tap.sh <screen> <selector>}"
FILE="docs/ui/$SCREEN.json"
[ -f "$FILE" ] || { echo "no map for '$SCREEN' (run tools/ui-map.sh $SCREEN)"; exit 2; }

COORDS=$(python3 - "$FILE" "$SEL" <<'PY'
import json, sys
nodes = json.load(open(sys.argv[1]))
sel = sys.argv[2].lower()
mode, _, needle = sel.partition(":")
matches = []
for n in nodes:
    if not n.get("enabled", True):
        continue
    hay = (n.get("id", "") if mode == "id" else (n.get("text", "") + " " + n.get("desc", ""))).lower()
    if needle in hay:
        matches.append(n)
matches.sort(key=lambda n: (not n.get("clickable"), n["bounds"]["cy"], n["bounds"]["cx"]))
if matches:
    print(matches[0]["bounds"]["cx"], matches[0]["bounds"]["cy"])
PY
)

if [ -z "$COORDS" ]; then echo "selector not found: $SEL"; exit 3; fi
echo "tap $SCREEN '$SEL' -> $COORDS"
# shellcheck disable=SC2086
"$ADB" shell input tap $COORDS
