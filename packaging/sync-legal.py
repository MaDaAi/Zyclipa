"""Copies the legal pages from the website repo (the single source) into the app, so the app shows exactly the
same documents offline. Run after editing them in ../zyclipra-dashboard (or the renamed zyclipa-dashboard)."""
import pathlib, shutil
APP = pathlib.Path(__file__).resolve().parent.parent
for name in ('zyclipa-dashboard', 'zyclipra-dashboard'):
    SITE = APP.parent / name
    if SITE.is_dir():
        break
else:
    raise SystemExit('website folder not found next to the app')
FILES = ['privacy.html', 'terms.html', 'eula.html', 'cookies.html', 'refunds.html', 'third-party-notices.txt', 'site.css', 'style.css']
for f in FILES:
    shutil.copyfile(SITE / f, APP / 'src/main/resources/web' / f)
print('synced', len(FILES), 'files from', SITE.name)
