#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

JAVAC="${JAVAC:-javac}"
JAVA="${JAVA:-java}"

case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) SEP=';' ;;
    *) SEP=':' ;;
esac

if [ ! -d build/classes ]; then
    ./scripts/build.sh
fi

rm -rf build/test-classes
mkdir -p build/test-classes

"$JAVAC" -encoding UTF-8 -source 1.8 -target 1.8 \
    -cp "build/classes${SEP}jars/*" -d build/test-classes \
    test/cloudsim/ext/datacenter/LoadBalancerConfigTest.java

"$JAVA" -cp "build/classes${SEP}build/test-classes" \
    cloudsim.ext.datacenter.LoadBalancerConfigTest
