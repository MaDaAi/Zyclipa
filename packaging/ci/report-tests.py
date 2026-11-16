"""CI helper: prints failing tests as GitHub annotations (visible on the public run page without signing in)."""
import glob
import html
import re

reports = glob.glob('target/surefire-reports/TEST-*.xml')
if not reports:
    print('::error::No test reports - the build failed before the tests ran (compile or dependency problem).')
for p in reports:
    x = open(p, encoding='utf-8', errors='replace').read()
    for m in re.finditer(r'<testcase name="([^"]+)" classname="([^"]+)"[^>]*>\s*<(failure|error)(?: message="([^"]*)")?[^>]*>([^<]{0,800})', x):
        msg = html.unescape((m.group(4) or '') + ' ' + m.group(5))
        msg = ' '.join(msg.split())[:600]
        print(f'::error title={m.group(2)}.{m.group(1)}::{msg}')
