#!/bin/sh
# Zyclipa Maven launcher: uses (and if needed downloads) Maven 3.9.9 inside this project's
# .tools folder, so nothing has to be installed system-wide.
set -e
BASEDIR=$(cd "$(dirname "$0")" && pwd)
MVN_HOME="$BASEDIR/.tools/apache-maven-3.9.9"
if [ ! -x "$MVN_HOME/bin/mvn" ]; then
  echo "Downloading Maven 3.9.9 into .tools ..."
  mkdir -p "$BASEDIR/.tools"
  curl -fsSL -o "$BASEDIR/.tools/maven.zip" https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.zip
  (cd "$BASEDIR/.tools" && unzip -q -o maven.zip)
fi
cd "$BASEDIR"
exec "$MVN_HOME/bin/mvn" "$@"
