#!/usr/bin/env python3
"""Regenerate every raster icon with the bold duotone palette.

Each icon keeps its silhouette (alpha) but gets a vivid two-hue gradient
plus bright highlights, grouped by category so the set stays coherent.
"""
from PIL import Image
import glob, os, re

# category -> (start hue, end hue, highlight strength)
CATS = [
    (r'^(p1|shortcut_p1|mode_powersave)',            ('#38BDF8', '#6366F1')),
    (r'^(p2|shortcut_p2|mode_balance)',              ('#34D399', '#22D3EE')),
    (r'^(p3|shortcut_p3|mode_performance)',          ('#A3E635', '#4ADE80')),
    (r'^(p4|shortcut_p4|mode_fast)',                 ('#FBBF24', '#FB923C')),
    (r'^b_0',                                         ('#F43F5E', '#FB7185')),
    (r'^b_1',                                         ('#FBBF24', '#FB923C')),
    (r'^b_2',                                         ('#A3E635', '#4ADE80')),
    (r'^b_3',                                         ('#34D399', '#2DD4BF')),
    (r'^(battery|charge|ic_capacity|ic_bat_stats)',    ('#34D399', '#A3E635')),
    (r'^power_',                                     ('#F43F5E', '#FB7185')),
    (r'^(app_home|app_menu|app_settings|graph|settings|auto)', ('#8B5CF6', '#22D3EE')),
    (r'(cpu|gpu|memory|harddisk|process|ic_menu|fw_float|ic_processes|ic_clock|ic_temperature|ic_voltage|linux|icon_chart|icon_android)', ('#8B5CF6', '#38BDF8')),
    (r'freeze',                                      ('#22D3EE', '#8B5CF6')),
    (r'(file|folder|kr_|save|swap_|source_)',         ('#2DD4BF', '#38BDF8')),
    (r'(warn|danger|question)',                      ('#FBBF24', '#FB923C')),
    (r'(dialog_)',                                   ('#8B5CF6', '#6366F1')),
    (r'^(theme_)',                                   ('#E879F9', '#8B5CF6')),
    # utility: keep readable, neutral slate
    (r'^(add|delete|close|arrow|check|menu_loading|legend)', ('#94A3B8', '#CBD5E1')),
]
DEFAULT = ('#8B5CF6', '#22D3EE')

def hex2rgb(h):
    h = h.lstrip('#')
    return tuple(int(h[i:i+2], 16) for i in (0, 2, 4))

def mix(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(int(a[i] + (b[i]-a[i])*t) for i in range(3))

def category(name):
    for pat, pair in CATS:
        if re.search(pat, name):
            return pair
    return DEFAULT

def process(path):
    name = os.path.basename(path)
    c1, c2 = (hex2rgb(c) for c in category(name))
    im = Image.open(path).convert('RGBA')
    w, h = im.size
    px = im.load()
    out = Image.new('RGBA', (w, h))
    op = out.load()
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a == 0:
                op[x, y] = (0, 0, 0, 0)
                continue
            lum = (0.2126*r + 0.7152*g + 0.0722*b) / 255.0
            pos = (x + y) / max(1, (w + h - 2))
            base = mix(c1, c2, pos)
            # dark pixels keep the gradient, bright pixels lift to white
            lift = min(1.0, (lum ** 1.4) * 1.15)
            color = mix(base, (255, 255, 255), lift)
            op[x, y] = (color[0], color[1], color[2], a)
    out.save(path)

count = 0
for p in glob.glob('app/src/main/res/drawable*/*.png'):
    process(p)
    count += 1
print(f'recolorized {count} PNG icons')
