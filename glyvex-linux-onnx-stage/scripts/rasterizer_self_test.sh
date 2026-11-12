#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$HERE/scripts/build_ml.sh"
CP="$HERE/target/classes:$(cat "$HERE/target/classpath.txt")"
java -cp "$CP" com.glyvex.ml.RasterizerSelfTest
