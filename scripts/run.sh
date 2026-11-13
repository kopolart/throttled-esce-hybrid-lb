#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

SCENARIO="${1:-1}"
JAVA="${JAVA:-java}"

case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) SEP=';' ;;
    *) SEP=':' ;;
esac

if [ ! -d build/classes ]; then
    ./scripts/build.sh
fi

"$JAVA" -Dlb.scenario="$SCENARIO" \
    -cp "build/classes${SEP}jars/*" \
    cloudsim.ext.gui.GuiMain
