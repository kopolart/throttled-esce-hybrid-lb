#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

JAVAC="${JAVAC:-javac}"

if ! command -v "$JAVAC" >/dev/null 2>&1; then
    echo "javac not found. Install JDK 8 or set JAVA_HOME/JAVAC." >&2
    exit 1
fi

rm -rf build/classes
mkdir -p build/classes
find src -name '*.java' > build/sources.txt

"$JAVAC" -encoding UTF-8 -source 1.8 -target 1.8 \
    -cp "jars/*" -d build/classes @build/sources.txt

cp -r src/resources/. build/classes/

echo "Build complete: build/classes"
