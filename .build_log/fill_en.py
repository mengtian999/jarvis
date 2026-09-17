# -*- coding: utf-8 -*-
"""Backfill English values into the default values/strings.xml using the
official English strings.xml as the source of truth. Only touches entries
whose current value contains CJK characters. List unresolved keys to a file."""
import re, io

LOCAL = r'D:\Code\IM\OpenMinis\OpenMinis-main\src\android\app\src\main\res\values\strings.xml'
OFFICIAL = r'D:\Code\IM\OpenMinis\OpenMinis-main\.build_log\raw_values_jsdelivr.xml'
OUT_UNRES = r'D:\Code\IM\OpenMinis\OpenMinis-main\.build_log\unresolved_en.txt'

def read(p):
    return io.open(p, encoding='utf-8').read()

def write(p, s):
    with io.open(p, 'w', encoding='utf-8', newline='\n') as f:
        f.write(s)

local = read(LOCAL)
official = read(OFFICIAL)

cjk = re.compile(u'[\u4e00-\u9fff\u3400-\u4dbf\uf900-\ufaff\u3000-\u303f\uff00-\uffef]')

# official maps
off_str = {}
for m in re.finditer(r'<string\s+name="([^"]+)"\s*[^>]*>(.*?)</string>', official, re.S):
    off_str[m.group(1)] = m.group(2)
off_plur = {}
for m in re.finditer(r'<plurals\s+name="([^"]+)"\s*[^>]*>.*?</plurals>', official, re.S):
    off_plur[m.group(1)] = m.group(0)

# collect local targets
targets = []  # (start, end, key, new_value)
for m in re.finditer(r'(<string\s+name="([^"]+)"\s*[^>]*>)(.*?)(</string>)', local, re.S):
    head, key, val, tail = m.group(1), m.group(2), m.group(3), m.group(4)
    if cjk.search(val):
        if key in off_str:
            targets.append((m.start(), m.end(), m.group(0), head + off_str[key] + tail, None))
        else:
            targets.append((m.start(), m.end(), None, None, key))

plur_targets = []
for m in re.finditer(r'<plurals\s+name="([^"]+)"\s*[^>]*>.*?</plurals>', local, re.S):
    blk = m.group(0)
    if cjk.search(blk):
        if m.group(1) in off_plur:
            plur_targets.append((m.start(), m.end(), blk, off_plur[m.group(1)]))
        # unres plurals handled separately? keep simple

print('string replaces: %d, plurals replaces: %d' % (len([t for t in targets if t[3]]), len(plur_targets)))
unres = [t[4] for t in targets if t[3] is None]
print('unresolved string keys: %d' % len(unres))

# apply plurals first (before string replace to keep offsets sane: both replace based on spans, we rebuild from spans sorted desc)
edits = [(s, e, new) for (s, e, old, new, key) in targets if new is not None]
edits += [(s, e, new) for (s, e, old, new) in plur_targets]
edits.sort(key=lambda x: x[0], reverse=True)
out = local
for s, e, n in edits:
    out = out[:s] + n + out[e:]

write(LOCAL, out)
with io.open(OUT_UNRES, 'w', encoding='utf-8') as f:
    for k in unres:
        # find current chinese value for context
        mm = re.search(r'<string\s+name="' + re.escape(k) + r'"\s*[^>]*>(.*?)</string>', out, re.S)
        v = mm.group(1).strip().replace('\n', ' ') if mm else '?'
        f.write(u'%s\t%s\n' % (k, v))
print('written.')

# sanity: count remaining CJK strings
remaining = 0
for m in re.finditer(r'<string\s+name="([^"]+)"\s*[^>]*>(.*?)</string>', out, re.S):
    if cjk.search(m.group(2)):
        remaining += 1
print('remaining CJK <string> entries after replace: %d' % remaining)