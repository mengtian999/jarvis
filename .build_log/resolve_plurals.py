# -*- coding: utf-8 -*-
"""Backfill missing <plurals> blocks into default values/strings.xml.
Scans R.plurals.* usages in Kotlin + @plurals/ refs in XML; copies full
<plurals> bodies from the first translation dir that defines them."""
import re
import io
import os

ROOT = r'D:\Code\IM\OpenMinis\OpenMinis-main\src\android\app\src\main'
BASE = os.path.join(ROOT, 'res', 'values', 'strings.xml')
PRIORITY = ['zh', 'ru', 'ja', 'ko', 'fr', 'de', 'sr', 'tr', 'es']

def read(p):
    with io.open(p, 'r', encoding='utf-8') as f:
        return f.read()

base = read(BASE)
base_plur = set(re.findall(r'<plurals\s+name="([^"]+)"', base))

# collect plurals blocks from translations in priority order
blocks = {}      # name -> full block source
for d in sorted([x for x in os.listdir(os.path.join(ROOT,'res')) if x.startswith('values-')],
                key=lambda x: (PRIORITY.index(x[len('values-'):].split('-')[0]) if x[len('values-'):].split('-')[0] in PRIORITY else 100)):
    p = os.path.join(ROOT, 'res', d, 'strings.xml')
    if not os.path.isfile(p):
        continue
    text = read(p)
    for m in re.finditer(r'<plurals\s+name="([^"]+)"[^>]*>.*?</plurals>', text, re.S):
        if m.group(1) not in blocks:
            blocks[m.group(1)] = m.group(0)

# scan references
need = set()
for r, dirs, fs in os.walk(os.path.join(ROOT, 'java')):
    for fn in fs:
        if fn.endswith('.kt'):
            t = read(os.path.join(r, fn))
            for m in re.finditer(r'R\.plurals\.([A-Za-z0-9_]+)', t):
                need.add(m.group(1))
for r, dirs, fs in os.walk(os.path.join(ROOT, 'res')):
    for fn in fs:
        if fn.endswith('.xml'):
            t = read(os.path.join(r, fn))
            for m in re.finditer(r'@plurals/([A-Za-z0-9_]+)', t):
                need.add(m.group(1))

missing = sorted(k for k in need if k not in base_plur)
print('needed plurals: %d, missing: %d' % (len(need), len(missing)))
for k in missing:
    print(('OK ' if k in blocks else 'MISS ') + k)

if missing:
    addition = ['', '    <!-- plurals backfilled -->']
    for k in missing:
        if k in blocks:
            blk = blocks[k]
            blk = '\n'.join(('    ' + l) for l in blk.strip().split('\n'))
            addition.append(blk)
        else:
            addition.append('    <plurals name="%s"><item quantity="other">%d</item></plurals>' % (k, 0))
    idx = base.rfind('</resources>')
    new = base[:idx] + '\n'.join(addition) + '\n    ' + base[idx:]
    with io.open(BASE, 'w', encoding='utf-8', newline='\n') as f:
        f.write(new)
    print('wrote %d plurals' % len(missing))
else:
    print('nothing to do')