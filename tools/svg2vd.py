"""Convert Tabler outline SVGs (24x24, stroke 2, round caps) to Android vector drawables.
Usage: svg2vd.py SRC_DIR OUT_DIR name=out[:#AARRGGBB] ...
Handles path, circle, line, rect, polyline; skips the stroke="none" bounding box."""
import os, re, sys
import xml.etree.ElementTree as ET

def num(v): return float(v or 0)

def to_d(el):
    tag = el.tag.split('}')[-1]
    a = el.attrib
    if tag == 'path':
        return a['d']
    if tag == 'circle':
        cx, cy, r = num(a['cx']), num(a['cy']), num(a['r'])
        return f"M{cx - r},{cy}a{r},{r} 0 1,0 {2 * r},0a{r},{r} 0 1,0 {-2 * r},0"
    if tag == 'line':
        return f"M{a['x1']},{a['y1']}L{a['x2']},{a['y2']}"
    if tag == 'polyline':
        pts = a['points'].split()
        return 'M' + 'L'.join(pts)
    if tag == 'rect':
        x, y, w, h = num(a.get('x')), num(a.get('y')), num(a['width']), num(a['height'])
        rx = num(a.get('rx'))
        if rx:
            return (f"M{x + rx},{y}h{w - 2 * rx}a{rx},{rx} 0 0 1 {rx},{rx}v{h - 2 * rx}"
                    f"a{rx},{rx} 0 0 1 {-rx},{rx}h{-(w - 2 * rx)}a{rx},{rx} 0 0 1 {-rx},{-rx}"
                    f"v{-(h - 2 * rx)}a{rx},{rx} 0 0 1 {rx},{-rx}z")
        return f"M{x},{y}h{w}v{h}h{-w}z"
    raise ValueError(f"unsupported element {tag}")

src, out = sys.argv[1], sys.argv[2]
for spec in sys.argv[3:]:
    name, rest = spec.split('=')
    dest, _, color = rest.partition(':')
    color = color or '#FFFFFFFF'
    root = ET.parse(os.path.join(src, name + '.svg')).getroot()
    paths = []
    for el in root.iter():
        if el is root or el.attrib.get('stroke') == 'none':
            continue
        paths.append(to_d(el))
    body = '\n'.join(
        f'    <path\n        android:pathData="{d}"\n        android:strokeColor="{color}"\n'
        f'        android:strokeWidth="2"\n        android:strokeLineCap="round"\n'
        f'        android:strokeLineJoin="round" />' for d in paths)
    xml = (f'<?xml version="1.0" encoding="utf-8"?>\n<!-- Tabler Icons "{name}" (MIT), converted from SVG. -->\n'
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
           '    android:width="24dp"\n    android:height="24dp"\n'
           '    android:viewportWidth="24"\n    android:viewportHeight="24">\n'
           f'{body}\n</vector>\n')
    open(os.path.join(out, dest + '.xml'), 'w', newline='\n').write(xml)
    print(dest, len(paths), 'paths')
