# -*- coding: utf-8 -*-
"""Count Chinese-valued strings in the default values/strings.xml and dump them."""
import re, io

p = r'D:\Code\IM\OpenMinis\OpenMinis-main\src\android\app\src\main\res\values\strings.xml'
d = io.open(p, encoding='utf-8').read()

cjk = re.compile(r'[\u4e00-\u9fff\u3000-\u303f\uff00-\uffef]')
items = re.findall(r'<string\s+name="([^"]+)"\s*[^>]*>(.*?)</string>', d, re.S)

cn = []
for k, v in items:
    if cjk.search(v):
        cn.append((k, v.strip()))

print('total <string> entries: %d' % len(items))
print('chinese-valued entries: %d' % len(cn))
noncn = set(k for k, v in items) - set(k for k, v in cn)
print('non-chinese entries: %d' % len(noncn))
# dump list to file
with io.open(r'D:\Code\IM\OpenMinis\OpenMinis-main\.build_log\chinese_strings.txt', 'w', encoding='utf-8') as f:
    for k, v in cn:
        f.write(u'%s\t%s\n' % (k, v.replace('\n', ' ')))
print('written chinese_strings.txt')