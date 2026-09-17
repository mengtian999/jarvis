# -*- coding: utf-8 -*-
"""Rebuild the default values/strings.xml from the official English source,
then apply local overrides (brand, apostrophe escaping, labels)."""
import re, io

OFF = r'D:\Code\IM\OpenMinis\OpenMinis-main\.build_log\raw_values_jsdelivr.xml'
LOCAL = r'D:\Code\IM\OpenMinis\OpenMinis-main\src\android\app\src\main\res\values\strings.xml'

d = io.open(OFF, encoding='utf-8').read()
orig_len = len(d)

# 1) escape apostrophes inside <string> values (aapt2 errors on unescaped ones)
def esc_string(m):
    head, val, tail = m.group(1), m.group(2), m.group(3)
    val2 = re.sub(r"(?<!\\)'", r"\\'", val)
    return head + val2 + tail
d = re.sub(r'(<string\s+name="[^"]*"\s*[^>]*>)(.*?)(</string>)', esc_string, d, flags=re.S)

# 2) brand: Minis -> Jarvis
d = re.sub(r'\bMinis\b', 'Jarvis', d)

# 3) welcome titles (brand replacement already turns 'Welcome to Minis' into
#    'Welcome to Jarvis'; also explicit override)
d = re.sub(r'<string name="sessionlist_welcome_title">(.*?)</string>',
           '<string name="sessionlist_welcome_title">Welcome to Jarvis</string>', d)
d = re.sub(r'<string name="onboarding_welcome_title">(.*?)</string>',
           '<string name="onboarding_welcome_title">Welcome to Jarvis</string>', d)

# 4) labels we added locally (manifest references them)
if 'name="a11y_service_label"' not in d:
    d = d.rstrip()
    d = d[:-len('</resources>')] + '\n    <string name="a11y_service_label">Jarvis Accessibility</string>\n    <string name="webapp_activity_label">Jarvis Web App</string>\n    </resources>\n'
else:
    d = re.sub(r'<string name="a11y_service_label">(.*?)</string>',
               '<string name="a11y_service_label">Jarvis Accessibility</string>', d)
    d = re.sub(r'<string name="webapp_activity_label">(.*?)</string>',
               '<string name="webapp_activity_label">Jarvis Web App</string>', d)

with io.open(LOCAL, 'w', encoding='utf-8', newline='\n') as f:
    f.write(d)
print('rebuilt. official len %d -> local %d' % (orig_len, len(d)))
print('app_name:', re.search(r'<string name="app_name">(.*?)</string>', d).group(1))
print('welcome:', re.search(r'<string name="sessionlist_welcome_title">(.*?)</string>', d).group(1))
print('a11y label exists:', 'name="a11y_service_label"' in d, '| webapp exists:', 'name="webapp_activity_label"' in d)
cjk = len(re.findall(u'[\u4e00-\u9fff]', d))
print('CJK chars remaining:', cjk)
# find CJK string values
cjkval = [m.group(1) for m in re.finditer(r'<string\s+name="([^"]+)"\s*[^>]*>(.*?)</string>', d, re.S) if re.search(u'[\u4e00-\u9fff]', m.group(2))]
print('CJK string entries:', cjkval)