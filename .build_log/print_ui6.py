# -*- coding: utf-8 -*-
import re, io
p = r'D:\Code\IM\OpenMinis\OpenMinis-main\.build_log\r13.xml'
d = io.open(p, encoding='utf-8', errors='replace').read()
# print all nodes that are clickable or have content-desc
for m in re.finditer(r'<node[^>]*>', d):
    n = m.group(0)
    md = re.search(r'content-desc="([^"]*)"', n)
    mc = re.search(r'clickable="([^"]*)"', n)
    mb = re.search(r'bounds="(\[[^"]*?\])"', n)
    mt = re.search(r'text="([^"]*)"', n)
    desc = md.group(1) if md else ''
    click = mc.group(1) if mc else ''
    bounds = mb.group(1) if mb else ''
    text = mt.group(1) if mt else ''
    if click == 'true' or desc.strip():
        print('click=%s desc=%r text=%r bounds=%s' % (click, desc, text, bounds))
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