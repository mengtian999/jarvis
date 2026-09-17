# -*- coding: utf-8 -*-
"""Backfill missing <string> resources into the (trimmed) default values/strings.xml
from the complete values-zh/strings.xml translation. Keeps existing base entries,
appends only keys that are missing from the base default file."""
import re
import io

def read(path):
    with io.open(path, 'r', encoding='utf-8') as f:
        return f.read()

base_path = r'D:\Code\IM\OpenMinis\OpenMinis-main\src\android\app\src\main\res\values\strings.xml'
src_path = r'D:\Code\IM\OpenMinis\OpenMinis-main\src\android\app\src\main\res\values-zh\strings.xml'

base = read(base_path)
src = read(src_path)

string_re = re.compile(r'<string\s+name="([^"]+)"[^>]*>(.*?)</string>', re.S)
base_items = string_re.findall(base)
src_items = string_re.findall(src)

base_keys = set(k for k, _ in base_items)
missing = [(k, v) for k, v in src_items if k not in base_keys]
print('base <string> entries: %d' % len(base_items))
print('source <string> entries: %d' % len(src_items))
print('missing from base: %d' % len(missing))

if not missing:
    print('nothing to do')
    raise SystemExit(0)

addition = ['', '    <!-- Backfilled from values-zh (keys absent from this mirror) -->']
for key, val in missing:
    text = val.strip()
    lines = text.split('\n')
    lines = [l.strip() for l in lines]
    text = '\n'.join(lines)
    addition.append('    <string name="%s">%s</string>' % (key, text))

idx = None
for m in re.finditer(r'</resources>', base):
    idx = m.start()
if idx is None:
    raise SystemExit('no </resources> found')

new_content = base[:idx] + '\n'.join(addition) + '\n    ' + base[idx:]
with io.open(base_path, 'w', encoding='utf-8', newline='\n') as f:
    f.write(new_content)
print('done, wrote %d backfilled strings' % len(missing))