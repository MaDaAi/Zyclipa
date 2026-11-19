#!/usr/bin/env bash
# Build the Zyclipa Flatpak (same layout as MultiMini).
#   dist/Zyclipa-<VERSION>-Linux-x86_64.flatpak (+ .sha256): a bundle that remembers the Zyclipa update
#     repository, so a file install still updates in-app or with `flatpak update`.
#   build/linux-site/: the signed OSTree repository + .flatpakref/.flatpakrepo for GitHub Pages, only when
#     FLATPAK_GPG_KEY_ID is set (release builds in CI).
# Needs target/zyclipa.jar built on Linux first (mvn -DskipTests package).
set -euo pipefail
cd "$(dirname "$0")/.."
repo_url=${ZYCLIPA_REPO_URL:-https://madaai.github.io/Zyclipa/repo/}
pubkey=packaging/linux/zyclipa-repo.gpg
version=$(python3 -c "import re;print(re.search(r'<artifactId>zyclipa</artifactId>\s*<version>([^<]+)<',open('pom.xml').read()).group(1))")

# Stage the files the manifest installs.
rm -rf build/linux-source
mkdir -p build/linux-source
cp target/zyclipa.jar packaging/linux/zyclipa packaging/linux/io.github.MaDaAi.Zyclipa.desktop \
   packaging/linux/io.github.MaDaAi.Zyclipa.metainfo.xml LICENSE packaging/THIRD-PARTY-NOTICES.txt build/linux-source/
cp src/main/resources/ui/icon-256.png src/main/resources/ui/icon-128.png build/linux-source/

if command -v flatpak-builder >/dev/null; then builder=(flatpak-builder); else builder=(flatpak run org.flatpak.Builder); fi
sign=()
if [ -n "${FLATPAK_GPG_KEY_ID:-}" ]; then
    sign=(--gpg-sign="$FLATPAK_GPG_KEY_ID")
    [ -n "${FLATPAK_GPG_HOMEDIR:-}" ] && sign+=(--gpg-homedir="$FLATPAK_GPG_HOMEDIR")
fi
"${builder[@]}" --user --force-clean --disable-rofiles-fuse "${sign[@]}" --default-branch=stable --repo=build/linux-repo \
    build/flatpak packaging/linux/io.github.MaDaAi.Zyclipa.json

mkdir -p dist
bundle="dist/Zyclipa-$version-Linux-x86_64.flatpak"
bundle_args=(--runtime-repo=https://flathub.org/repo/flathub.flatpakrepo)
# Only signed (release) builds remember the update repository: Flatpak refuses an unsigned bundle that claims a
# signed source, so CI test bundles stay plain.
if [ ${#sign[@]} -gt 0 ] && [ -f "$pubkey" ]; then bundle_args+=(--repo-url="$repo_url" --gpg-keys="$pubkey"); fi
flatpak build-bundle "${bundle_args[@]}" build/linux-repo "$bundle" io.github.MaDaAi.Zyclipa stable
(cd dist && sha256sum "$(basename "$bundle")" > "$(basename "$bundle").sha256")
echo "Bundle: $bundle"

if [ ${#sign[@]} -gt 0 ]; then
    flatpak build-update-repo "${sign[@]}" --generate-static-deltas --prune --prune-depth=2 \
        --title="Zyclipa" --default-branch=stable build/linux-repo
    rm -rf build/linux-site
    mkdir -p build/linux-site
    cp -a build/linux-repo build/linux-site/repo
    key=$(base64 -w0 "$pubkey")
    cat > build/linux-site/zyclipa.flatpakref <<REF
[Flatpak Ref]
Name=io.github.MaDaAi.Zyclipa
Branch=stable
Title=Zyclipa
Url=$repo_url
SuggestRemoteName=zyclipa
RuntimeRepo=https://flathub.org/repo/flathub.flatpakrepo
IsRuntime=false
GPGKey=$key
REF
    cat > build/linux-site/zyclipa.flatpakrepo <<REF
[Flatpak Repo]
Title=Zyclipa
Url=$repo_url
Homepage=https://github.com/MaDaAi/Zyclipa
GPGKey=$key
REF
    cat > build/linux-site/index.html <<'HTML'
<!doctype html><meta charset="utf-8"><title>Zyclipa for Linux</title>
<h1>Zyclipa for Linux</h1>
<p>Install once, then Zyclipa updates itself (Settings › About and Privacy › Updates) or through your software centre:</p>
<pre>flatpak install --user https://madaai.github.io/Zyclipa/zyclipa.flatpakref</pre>
<p><a href="zyclipa.flatpakref">zyclipa.flatpakref</a> · <a href="https://github.com/MaDaAi/Zyclipa/releases">All releases</a></p>
HTML
    touch build/linux-site/.nojekyll
    printf 'Site: %s\n' "$PWD/build/linux-site"
fi
