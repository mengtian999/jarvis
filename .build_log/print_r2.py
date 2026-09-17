# -*- coding: utf-8 -*-
import re, io
p = r'D:\Code\IM\OpenMinis\OpenMinis-main\.build_log\ui7.xml'
d = io.open(p, encoding='utf-8', errors='replace').read()
seen = []
for m in re.finditer(r'text="([^"]*)"', d):
    t = m.group(1)
    if t.strip():
        seen.append(t)
for t in seen:
    try:
        print(t)
    except Exception:
        pass
print('--- total non-empty text nodes:', len(seen))