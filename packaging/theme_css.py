"""Gives zyclipa.css a light theme: every hard-coded colour outside .root becomes a looked-up colour (-c-RRGGBB)
defined in .root (dark, unchanged) and in .root.light (converted). Re-run after adding colours to the stylesheet.
The conversion matches io.zyclipa.ui.Theme.light()."""
import colorsys, pathlib, re

CSS = pathlib.Path(__file__).resolve().parent.parent / 'src/main/resources/ui/zyclipa.css'
# Hand-picked light versions of the named palette (the rest is converted by light()).
PALETTE = {'-bg': '#F3F4F7', '-rail': '#E9EBF0', '-card': '#FFFFFF', '-card-head': '#F7F8FA', '-well': '#ECEEF2', '-line': '#D8DCE3',
           '-text': '#1C2027', '-muted': '#5B6270', '-faint': '#858B97', '-icon-color': '#2A2F38', '-danger': '#C82434',
           '-ok': '#0E9468', '-warn': '#A86B10'}
KEEP = {'#4285F4', '#F1F3F4', '#1F1F1F', '#FFFFFF', '#000000'}   # Google button colours, pure white/black


def light(hexc):
    r, g, b = (int(hexc[i:i + 2], 16) / 255 for i in (1, 3, 5))
    h, l, s = colorsys.rgb_to_hls(r, g, b)
    if s < 0.30 or l < 0.22:            # surfaces and greys: flip lightness, keep a hint of the tint
        l2 = 0.97 - (l - 0.08) * 0.55 if l < 0.5 else max(0.10, 1.0 - l)
        s2 = min(s, 0.12) if s < 0.30 else s * 0.6
    else:                                # colours: keep, but dark enough to read on a light background
        l2, s2 = min(l, 0.45), s
    r2, g2, b2 = colorsys.hls_to_rgb(h, max(0, min(1, l2)), max(0, min(1, s2)))
    return '#%02X%02X%02X' % (round(r2 * 255), round(g2 * 255), round(b2 * 255))


css = CSS.read_text(encoding='utf-8')
css = re.sub(r'\n/\* ---------- light theme \(generated\) ---------- \*/.*\Z', '', css, flags=re.S)
root_end = css.index('}', css.index('.root {')) + 1
head, rules = css[:root_end], css[root_end:]
# Undo a previous run so it can be re-applied cleanly.
head = re.sub(r'\n    -c-[0-9A-F]{6}: #[0-9A-F]{6};', '', head)
rules = re.sub(r'-c-([0-9A-F]{6})\b', lambda m: '#' + m.group(1), rules)
found = []
def swap(m):
    c = m.group(0).upper()
    if len(c) == 4:
        c = '#' + ''.join(ch * 2 for ch in c[1:])
    if c in KEEP:
        return m.group(0)
    if c not in found:
        found.append(c)
    return '-c-' + c[1:]
rules = re.sub(r'#[0-9A-Fa-f]{6}\b|#[0-9A-Fa-f]{3}\b(?![0-9A-Fa-f])', swap, rules)
defs = ''.join(f'\n    -c-{c[1:]}: {c};' for c in found)
head = head[:-1].rstrip() + defs + '\n}'
light_block = '\n/* ---------- light theme (generated) ---------- */\n.root.light {\n' \
    + ''.join(f'    {k}: {v};\n' for k, v in PALETTE.items()) \
    + ''.join(f'    -c-{c[1:]}: {light(c)};\n' for c in found) + '}\n'
CSS.write_text(head + rules.rstrip() + '\n' + light_block, encoding='utf-8')
print(len(found), 'colours converted')
