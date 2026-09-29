#!/usr/bin/env python3
"""Convert a uiautomator dump into agent-friendly JSON.

Usage: uix2json.py <dump.xml> <out.json>
"""
import json
import re
import sys
import xml.etree.ElementTree as ET


def parse_bounds(text):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", text or "")
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return {"x1": x1, "y1": y1, "x2": x2, "y2": y2,
            "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2}


def main():
    src, dst = sys.argv[1], sys.argv[2]
    root = ET.parse(src).getroot()
    nodes = []
    for node in root.iter("node"):
        bounds = parse_bounds(node.get("bounds", ""))
        if not bounds:
            continue
        text = (node.get("text") or "").strip()
        rid = (node.get("resource-id") or "").strip()
        desc = (node.get("content-desc") or "").strip()
        if not (text or rid or desc):
            continue
        nodes.append({
            "text": text,
            "id": rid.replace("com.omarea.vtools:id/", ""),
            "desc": desc,
            "class": node.get("class", ""),
            "clickable": node.get("clickable") == "true",
            "enabled": node.get("enabled") == "true",
            "bounds": bounds,
        })
    with open(dst, "w") as fh:
        json.dump(nodes, fh, indent=1)
    print(f"{dst}: {len(nodes)} nodes")


if __name__ == "__main__":
    main()
