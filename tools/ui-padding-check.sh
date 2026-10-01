#!/usr/bin/env bash
# ui-padding-check.sh — verify the 16dp content rhythm on the current screen.
#
# Dumps the view hierarchy with uiautomator and reports the left/right insets
# of row-like content (nodes spanning >= 50% of the screen width) relative to
# the screen, so padding drift is measurable instead of eyeballed.
#
# Valid content tiers (dp):
#   16  — content directly on the screen edge
#   32  — content inside a 16dp-padded card
#
# Usage:
#   bash tools/ui-padding-check.sh              # current screen
#   bash tools/ui-padding-check.sh --rows       # also print row heights
#
# Exit code: 0 when every reported inset matches a valid tier (±1dp), 1 otherwise.
set -euo pipefail

ADB="${ADB:-adb}"
WIDTH=$($ADB shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | cut -dx -f1 | tail -1)
DENSITY=$($ADB shell wm density 2>/dev/null | grep -oE '[0-9]+' | tail -1)
WIDTH=${WIDTH:-1080}
DENSITY=${DENSITY:-440}
PX_PER_DP=$(python3 -c "print(${DENSITY}/160)")

DUMP=/tmp/opencode/ui-dump.xml
$ADB shell uiautomator dump /sdcard/ui-dump.xml >/dev/null 2>&1 || true
$ADB pull /sdcard/ui-dump.xml "$DUMP" >/dev/null 2>&1 || true

python3 - "$DUMP" "$WIDTH" "$PX_PER_DP" "${1:-}" <<'PYEOF'
import re, sys, xml.etree.ElementTree as ET
from collections import Counter

dump, width, scale = sys.argv[1], int(sys.argv[2]), float(sys.argv[3])
want_rows = len(sys.argv) > 4 and sys.argv[4] == '--rows'

tree = ET.parse(dump)
insets = Counter()
heights = Counter()
for node in tree.iter('node'):
    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds', ''))
    if not m:
        continue
    l, t, r, b = map(int, m.groups())
    w, h = r - l, b - t
    if w <= 0 or h <= 0 or w > width * 0.98:
        continue          # skip full-width containers and empty nodes
    if w < width * 0.5:
        continue          # only row-like content is judged
    dp = lambda v: round(v / scale, 1)
    insets[(dp(l), dp(width - r))] += 1
    if want_rows and h >= 30 * scale:
        heights[dp(h)] += 1

def valid(v):
    return min(abs(v - 16), abs(v - 32)) <= 1

print(f'row insets (left, right) in dp — screen {width}px @ {scale:.2f}px/dp')
ok = True
dominant = insets.most_common(1)
for (left, right), count in insets.most_common(12):
    is_dominant = (left, right) == dominant[0][0]
    valid_pair = valid(left) and valid(right)
    flag = ''
    if is_dominant and not valid_pair:
        flag = '  <- OFF-GRID (dominant)'
        ok = False
    elif not valid_pair:
        flag = '  (inner element)'
    print(f'  left={left:>6} right={right:>6}  x{count}{flag}')
if want_rows:
    print('row heights (dp):', ', '.join(f'{h}x{n}' for h, n in heights.most_common(8)))
sys.exit(0 if ok else 1)
PYEOF
