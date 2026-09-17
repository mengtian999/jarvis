# -*- coding: utf-8 -*-
import re
import io
p = r'D:\Code\IM\OpenMinis\OpenMinis-main\.build_log\r14.xml'
d = io.open(p, encoding='utf-8', errors='replace').read()
for m in re.findall(r'text="([^"]*)"[^>]*?class="android.widget.TextView"', d):
    if m.strip():
        print(m)