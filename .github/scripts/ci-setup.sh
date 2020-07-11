#!/usr/bin/env bash
set -e

if [ $# = 1 ]; then
  DIR="$1"
else
  DIR="$HOME/.bin"
fi
mkdir -p "$DIR"

export PATH="$DIR:$PATH"

COURSIER_VERSION="2.0.0-RC6-7"
MILL_VERSION="0.6.0"

if [ ! -x "$DIR/cs-$COURSIER_VERSION" ]; then
  curl -Lo "$DIR/cs-$COURSIER_VERSION" \
    "https://github.com/coursier/coursier/releases/download/v$COURSIER_VERSION/cs-x86_64-pc-linux"
  chmod +x "$DIR/cs-$COURSIER_VERSION"
  rm -f "$DIR/cs"
  ( cd "$DIR" && ln -s "cs-$COURSIER_VERSION" cs )
fi

"$DIR/cs" install --dir "$DIR" \
   "mill:$MILL_VERSION" \
   sbt-launcher

"$DIR/cs" bootstrap -o "$DIR/amm" \
   com.lihaoyi:ammonite_2.13.2:2.1.4 \
   -M ammonite.Main
