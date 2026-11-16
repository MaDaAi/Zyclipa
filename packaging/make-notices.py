"""Builds packaging/THIRD-PARTY-NOTICES.txt: the licences of everything bundled in the Zyclipa exe.

Run after changing dependencies:  python packaging/make-notices.py
It reads the licence files shipped inside the dependency jars (project-local .m2) and adds the full texts of
the Apache 2.0 and GPLv2 + Classpath Exception licences.
"""
import pathlib, urllib.request, zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
M2 = ROOT / '.m2' / 'repository'
OUT = ROOT / 'packaging' / 'THIRD-PARTY-NOTICES.txt'

COMPONENTS = [
    ('Java runtime (OpenJDK)', 'GPLv2 with the Classpath Exception', 'see the runtime\\legal folder next to Zyclipa.exe'),
    ('OpenJFX (JavaFX) 23.0.2', 'GPLv2 with the Classpath Exception', 'full text below'),
    ('Gson 2.15.0 (Google)', 'Apache License 2.0', 'full text below'),
    ('Bouncy Castle 1.86', 'Bouncy Castle Licence (MIT-style)', 'text below'),
    ('JNA 5.19.1', 'Apache License 2.0 (or LGPL 2.1, at your choice)', 'text below'),
    ('webrtc-java 0.20.0 (devopvoid)', 'Apache License 2.0', 'full text below'),
    ('WebRTC (Google, built into webrtc-java)', 'BSD 3-Clause + patent grant', 'text below'),
    ('NVIDIA Video Codec SDK header (in webrtc-java)', 'NVIDIA licence (permissive)', 'text below'),
]

def jar_text(rel, inner):
    for jar in (M2 / rel).glob('*.jar'):
        with zipfile.ZipFile(jar) as z:
            if inner in z.namelist():
                return z.read(inner).decode('utf-8', 'replace')
    raise SystemExit('missing ' + rel + ' ' + inner)

def fetch(url):
    return urllib.request.urlopen(url, timeout=30).read().decode('utf-8', 'replace')

parts = ['Zyclipa – third-party notices', '=' * 30, '',
         'Zyclipa itself is licensed under the MIT License (LICENSE.txt). It bundles these components:', '']
for name, lic, where in COMPONENTS:
    parts.append(f'- {name}: {lic} ({where})')
parts.append('\nFFmpeg is not bundled; Zyclipa uses a copy you install yourself, under its own licence.\n')

def section(title, text):
    parts.extend(['', '-' * 78, title, '-' * 78, text.strip(), ''])

section('Bouncy Castle', jar_text('org/bouncycastle/bcprov-jdk18on/1.86', 'META-INF/LICENSE.md'))
section('JNA', jar_text('net/java/dev/jna/jna/5.19.1', 'META-INF/LICENSE'))
w = 'dev/onvoid/webrtc/webrtc-java/0.20.0'
section('WebRTC', jar_text(w, 'META-INF/licenses/webrtc/LICENSE.md'))
section('WebRTC – additional IP rights grant (patents)', jar_text(w, 'META-INF/licenses/webrtc/PATENTS'))
section('NVIDIA Video Codec SDK header', jar_text(w, 'META-INF/licenses/nvenc/LICENSE'))
section('Apache License 2.0 (Gson, JNA, webrtc-java)', fetch('https://www.apache.org/licenses/LICENSE-2.0.txt'))
section('GPLv2 with the Classpath Exception (OpenJFX)', fetch('https://raw.githubusercontent.com/openjdk/jfx/master/LICENSE'))
OUT.write_text('\n'.join(parts), encoding='utf-8')
print(OUT, OUT.stat().st_size, 'bytes')
