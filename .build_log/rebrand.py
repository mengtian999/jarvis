# -*- coding: utf-8 -*-
import re, io
p = r'D:\Code\IM\OpenMinis\OpenMinis-main\src\android\app\src\main\res\values\strings.xml'
s = io.open(p, encoding='utf-8').read()
n, out = re.subn(r'\bMinis\b', 'Jarvis', s)
with io.open(p, 'w', encoding='utf-8', newline='\n') as f:
    f.write(out)
print('replaced prev:\bMinis\b occurrences:', n)
m = re.search(r'<string name="app_name">(.*?)</string>', out)
print('app_name:', m.group(1))
m2 = re.search(r'<string name="sessionlist_welcome_title">(.*?)</string>', out)
print('welcome:', m2.group(1))
# show lines still containing lowercase 'minis' (internal identifiers, must stay)
low = [ln.strip() for ln in out.splitlines() if 'minis' in ln.lower() and not ('Jarvis' in ln)]
print('lines with lowercase minis kept:')
for ln in low[:12]:
    print('  ', ln[:120])