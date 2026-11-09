#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/out"
rm -rf "$OUT"
mkdir -p "$OUT"

mapfile -t SOURCES < <(find "$ROOT/src" -type f -name '*.java' | sort)
javac --release 17 -d "$OUT" "${SOURCES[@]}"

echo "Built Java classes in: $OUT"
