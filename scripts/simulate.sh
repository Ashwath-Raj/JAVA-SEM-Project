#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

CHAR="${1:-C}"
shift || true

ONNX_DIR="$ROOT/glyvex-linux-onnx-stage"
CP="$ONNX_DIR/target/classes:$(cat "$ONNX_DIR/target/classpath.txt")"
CONFIG="$ROOT/config/glyvex-config.json"

echo "============================================================"
echo "GLYVEX: SIMULATING GESTURE '$CHAR'"
echo "============================================================"

python3 "$ROOT/scripts/simulate_gesture.py" "$CHAR" | \
  java --enable-native-access=ALL-UNNAMED \
    -cp "$CP" com.glyvex.app.GlyvexApplication \
    --config "$CONFIG" \
    --root "$ROOT" \
    "$@"
