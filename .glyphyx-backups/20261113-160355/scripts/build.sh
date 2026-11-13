#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "============================================================"
echo "GLYVEX: BUILDING APPLICATION & NATIVE COMPONENTS"
echo "============================================================"

# 1. Compile C evdev Touchpad Bridge
NATIVE_DIR="$ROOT/glyvex-linux-touchpad/native/linux/touchpad"
NATIVE_SRC="$NATIVE_DIR/glyvex_touchpad.c"
NATIVE_BIN="$NATIVE_DIR/glyvex-touchpad"

echo "[1/3] Compiling native Linux touchpad reader ($NATIVE_SRC)..."
gcc -Wall -Wextra -O2 "$NATIVE_SRC" -o "$NATIVE_BIN"
echo "✓ Native binary ready: $NATIVE_BIN"

# 2. Compile Java Subsystems
ONNX_DIR="$ROOT/glyvex-linux-onnx-stage"
CP_FILE="$ONNX_DIR/target/classpath.txt"

mkdir -p "$(dirname "$CP_FILE")"
echo "Resolving Maven runtime dependencies (including JavaFX)..."
mvn -q -f "$ONNX_DIR/pom.xml" dependency:build-classpath -Dmdep.outputFile="$CP_FILE"
if [[ ! -s "$CP_FILE" ]]; then
  echo "ERROR: Maven did not generate the runtime classpath: $CP_FILE" >&2
  exit 1
fi

echo "[2/3] Compiling Java classes (FSM, Capture, ML, Commands, UI, App)..."
java "$ONNX_DIR/scripts/CompileAll.java" "$ONNX_DIR/src" "$ONNX_DIR/target/classes" "$(cat "$CP_FILE")"
echo "✓ Java classes compiled to: $ONNX_DIR/target/classes"

# 3. Run Automated Regression Test Suite
echo "[3/3] Running automated regression test suite..."
java --enable-native-access=ALL-UNNAMED \
  -cp "$ONNX_DIR/target/classes:$(cat "$CP_FILE")" \
  com.glyvex.test.PipelineIntegrationTest

echo "============================================================"
echo "BUILD COMPLETE: All GLYVEX subsystems verified successfully!"
echo "============================================================"
