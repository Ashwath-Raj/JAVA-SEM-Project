#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ONNX_DIR="$ROOT/glyvex-linux-onnx-stage"
CP_FILE="$ONNX_DIR/target/classpath.txt"
[[ -s "$CP_FILE" ]] || { echo "Run ./scripts/build.sh first." >&2; exit 1; }
CP="$ONNX_DIR/target/classes:$ONNX_DIR/target/test-classes:$(cat "$CP_FILE")"
java --enable-native-access=ALL-UNNAMED -cp "$CP" com.glyvex.touchpad.demo.SelfTest
java --enable-native-access=ALL-UNNAMED -cp "$CP" com.glyvex.ml.RasterizerSelfTest
java --enable-native-access=ALL-UNNAMED -cp "$CP" com.glyvex.test.PipelineIntegrationTest
if [[ -f "$ONNX_DIR/target/test-classes/CommandRegistrySmokeTest.class" ]]; then
  java --enable-native-access=ALL-UNNAMED -cp "$CP" CommandRegistrySmokeTest
fi
