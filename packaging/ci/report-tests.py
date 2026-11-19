"""CI helper: prints failing tests as GitHub annotations (visible on the public run page without signing in)."""
import glob
import html
import re

reports = glob.glob('target/surefire-reports/TEST-*.xml')
if not reports:
    print('::error::No test reports - the build failed before the tests ran (compile or dependency problem).')
    # Show Maven's own error lines (the full log needs a GitHub sign-in).
    try:
        lines = open('build.log', encoding='utf-8', errors='replace').read().splitlines()
        errs = [l for l in lines if '[ERROR]' in l or 'BUILD FAILURE' in l or 'Exception' in l][:25]
        for l in errs or lines[-25:]:
            print('::error::' + l.strip()[:400])
    except OSError:
        pass
for p in reports:
    x = open(p, encoding='utf-8', errors='replace').read()
    for m in re.finditer(r'<testcase name="([^"]+)" classname="([^"]+)"[^>]*>\s*<(failure|error)(?: message="([^"]*)")?[^>]*>([^<]{0,800})', x):
        msg = html.unescape((m.group(4) or '') + ' ' + m.group(5))
        msg = ' '.join(msg.split())[:600]
        print(f'::error title={m.group(2)}.{m.group(1)}::{msg}')
