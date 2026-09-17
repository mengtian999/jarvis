# -*- coding: utf-8 -*-
"""Fix welcome titles across all locales: replace 'Minis' brand in
welcome_title strings with the local brand (Jarvis / 贾维斯).
Values/strings.xml (default) gets proper English wording."""
import io
import os
import re

ROOT = r'D:\Code\IM\OpenMinis\OpenMinis-main\src\android\app\src\main\res'
KEYS = ('onboarding_welcome_title', 'sessionlist_welcome_title')

def read(p):
    with io.open(p, 'r', encoding='utf-8') as f:
        return f.read()

def write(p, s):
    with io.open(p, 'w', encoding='utf-8', newline='\n') as f:
        f.write(s)

def fix_text(lang, text):
    if lang in ('zh', 'zh-rTW'):
        if lang == 'zh-rTW':
            return text.replace('Minis', '賈維斯')
        return text.replace('Minis', '贾维斯')
    return text.replace('Minis', 'Jarvis')

EN_DEFAULT = {
    'onboarding_welcome_title': 'Welcome to Jarvis',
    'sessionlist_welcome_title': 'Welcome to Jarvis',
}

for d in sorted(os.listdir(ROOT)):
    if not (d == 'values' or (d.startswith('values-') and d != 'values-night')):
        continue
    p = os.path.join(ROOT, d, 'strings.xml')
    if not os.path.isfile(p):
        continue
    raw = read(p)
    changed = []
    lang = d[len('values'):].lstrip('-')
    for key in KEYS:
        pat = re.compile(r'(<string name="%s">)(.*?)(</string>)' % key, re.S)
        m = pat.search(raw)
        if not m:
            continue
        old = m.group(2)
        new = fix_text(lang, old) if d != 'values' else EN_DEFAULT[key]
        if old != new:
            raw = raw[:m.start()] + m.group(1) + new + m.group(3) + raw[m.end():]
            changed.append('%s: %r -> %r' % (key, old, new))
    if changed:
        write(p, raw)
        for c in changed:
            print('%s :: %s' % (d, c))
    else:
        print('%s :: no change' % d)
print('done')