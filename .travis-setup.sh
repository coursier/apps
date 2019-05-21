#!/usr/bin/env bash
set -e

if [ $# = 1 ]; then
  DIR="$1"
else
  DIR="$HOME/.bin"
fi
mkdir -p "$DIR"

export PATH="$DIR:$PATH"

COURSIER_VERSION="1.1.0-M14-4"
MILL_VERSION="0.3.6"

if [ ! -x "$DIR/coursier-$COURSIER_VERSION" ]; then
  curl -Lo "$DIR/coursier-$COURSIER_VERSION" \
    "https://github.com/coursier/coursier/releases/download/v$COURSIER_VERSION/coursier"
  chmod +x "$DIR/coursier-$COURSIER_VERSION"
  rm -f "$DIR/coursier"
  ( cd "$DIR" && ln -s "coursier-$COURSIER_VERSION" coursier )
fi

MILL_CLASSPATH="$(coursier fetch --classpath "com.lihaoyi:mill-dev_2.12:$MILL_VERSION")"
coursier bootstrap -f "com.lihaoyi:mill-dev_2.12:$MILL_VERSION" \
  -o "$DIR/mill" \
  -M mill.MillMain \
  --property jna.nosys=true \
  --property MILL_VERSION="$MILL_VERSION" \
  --property MILL_CLASSPATH="$MILL_CLASSPATH"
