#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ONNX_DIR="$ROOT/glyvex-linux-onnx-stage"
NATIVE_SRC="$ROOT/glyvex-linux-touchpad/native/linux/touchpad/glyvex_touchpad.c"
NATIVE_BIN="$ROOT/glyvex-linux-touchpad/native/linux/touchpad/glyvex-touchpad"
CP_FILE="$ONNX_DIR/target/classpath.txt"
CLASSES="$ONNX_DIR/target/classes"
TEST_CLASSES="$ONNX_DIR/target/test-classes"

printf '\n============================================================\n'
printf 'GLYPHX: BUILDING DESKTOP RUNTIME\n'
printf '============================================================\n'
mkdir -p "$(dirname "$NATIVE_BIN")" "$ONNX_DIR/target" "$CLASSES" "$TEST_CLASSES"

echo "[1/4] Compiling native Linux touchpad reader..."
gcc -Wall -Wextra -O2 "$NATIVE_SRC" -o "$NATIVE_BIN"

echo "[2/4] Resolving Maven dependencies (JavaFX, ONNX Runtime, Jackson)..."
mvn -q -f "$ONNX_DIR/pom.xml" dependency:build-classpath -Dmdep.outputFile="$CP_FILE"
[[ -s "$CP_FILE" ]] || { echo "ERROR: Maven did not generate $CP_FILE" >&2; exit 1; }
CP="$(cat "$CP_FILE")"

echo "[3/4] Compiling Java runtime and native JavaFX interface..."
rm -rf "$CLASSES" "$TEST_CLASSES"
mkdir -p "$CLASSES" "$TEST_CLASSES"
java "$ONNX_DIR/scripts/CompileAll.java" "$ONNX_DIR/src" "$CLASSES" "$CP"

if [[ -f "$ONNX_DIR/tests/CommandRegistrySmokeTest.java" ]]; then
  javac -encoding UTF-8 -cp "$CLASSES:$CP" -d "$TEST_CLASSES" "$ONNX_DIR/tests/CommandRegistrySmokeTest.java"
fi

echo "[4/4] Running automated regression tests..."
RUNTIME_CP="$CLASSES:$TEST_CLASSES:$CP"
java --enable-native-access=ALL-UNNAMED -cp "$RUNTIME_CP" com.glyvex.touchpad.demo.SelfTest
java --enable-native-access=ALL-UNNAMED -cp "$RUNTIME_CP" com.glyvex.ml.RasterizerSelfTest
java --enable-native-access=ALL-UNNAMED -cp "$RUNTIME_CP" com.glyvex.test.PipelineIntegrationTest
if [[ -f "$TEST_CLASSES/CommandRegistrySmokeTest.class" ]]; then
  java --enable-native-access=ALL-UNNAMED -cp "$RUNTIME_CP" CommandRegistrySmokeTest
fi
printf '\nBUILD COMPLETE — JavaFX desktop runtime is ready.\n'
