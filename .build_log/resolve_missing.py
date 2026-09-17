# -*- coding: utf-8 -*-
"""Scan every @string/ reference in res/*.xml / AndroidManifest.xml and backfill
any missing key into the default values/strings.xml using the best available
translation (priority zh, ru, ja, ko, fr, de). Idempotent."""
import re
import io
import os

ROOT = r'D:\Code\IM\OpenMinis\OpenMinis-main\src\android\app\src\main'
BASE = os.path.join(ROOT, 'res', 'values', 'strings.xml')
LOCALES = ['zh', 'ru', 'ja', 'ko', 'fr', 'de']
PRIORITY = LOCALES + ['sr', 'tr', 'es', 'it', 'pt', 'nl', 'pl', 'id', 'th', 'vi', 'ar', 'hi', 'bn', 'fa', 'ur']
# also include every values-* folder that exists, in alphabetical-ish priority

def read(p):
    with io.open(p, 'r', encoding='utf-8') as f:
        return f.read()

def keys_of(xml):
    return dict(re.findall(r'<string\s+name="([^"]+)"\s*[^>]*>(.*?)</string>', xml, re.S))

base_xml = read(BASE)
base_map = keys_of(base_xml)

# gather translations
translations = {}  # key -> value  (first in priority)
lang_dirs = []
for d in os.listdir(os.path.join(ROOT, 'res')):
    if d.startswith('values-') and len(d) > len('values-'):
        lang_dirs.append(d)
# order by priority
def lang_prio(dirname):
    lang = dirname[len('values-'):].split('-')[0]
    if lang in PRIORITY:
        return PRIORITY.index(lang)
    return 100
lang_dirs.sort(key=lang_prio)
for d in lang_dirs:
    p = os.path.join(ROOT, 'res', d, 'strings.xml')
    if not os.path.isfile(p):
        continue
    for k, v in keys_of(read(p)).items():
        if k not in translations:
            translations[k] = v

# collect references
need = set()
string_ref = re.compile(r'@string/([A-Za-z0-9_]+)')
for r,d,fs in os.walk(os.path.join(ROOT, 'res')):
    for fn in fs:
        if fn.endswith('.xml'):
            xml = read(os.path.join(r, fn))
            for m in string_ref.finditer(xml):
                k = m.group(1)
                if not k.startswith('android'):
                    need.add(k)
mani = os.path.join(ROOT, 'AndroidManifest.xml')
if os.path.isfile(mani):
    for m in string_ref.finditer(read(mani)):
        k = m.group(1)
        if not k.startswith('android'):
            need.add(k)

# also scan kotlin for R.string usage
kotlin_ref = re.compile(r'R\.string\.([A-Za-z0-9_]+)')
for r,d,fs in os.walk(os.path.join(ROOT, 'java')):
    for fn in fs:
        if fn.endswith('.kt'):
            for m in kotlin_ref.finditer(read(os.path.join(r, fn))):
                need.add(m.group(1))
for r,d,fs in os.walk(os.path.join(ROOT, 'kotlin')):
    for fn in fs:
        if fn.endswith('.kt'):
            for m in kotlin_ref.finditer(read(os.path.join(r, fn))):
                need.add(m.group(1))

missing = sorted(k for k in need if k not in base_map)
print('referenced keys: %d, missing from base: %d' % (len(need), len(missing)))
logging = []
for k in missing:
    v = translations.get(k)
    if v is None:
        logging.append('NO TRANSLATION for %s' % k)
        v = 'PLACEHOLDER_%s' % k
    logging.append('%s = %s' % (k, v[:60].replace('\n',' ')))
print('\n'.join(logging))

if missing:
    addition = ['', '    <!-- Backfilled via resolve_missing (missing in this mirror) -->']
    for k in missing:
        v = translations.get(k, 'PLACEHOLDER_' + k).strip()
        v = '\n'.join(l.strip() for l in v.split('\n'))
        addition.append('    <string name="%s">%s</string>' % (k, v))
    idx = base_xml.rfind('</resources>')
    new = base_xml[:idx] + '\n'.join(addition) + '\n    ' + base_xml[idx:]
    with io.open(BASE, 'w', encoding='utf-8', newline='\n') as f:
        f.write(new)
    print('backfilled %d keys' % len(missing))
else:
    print('nothing to backfill')