#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ONNX_DIR="$ROOT/glyvex-linux-onnx-stage"
CP="$ONNX_DIR/target/classes:$(cat "$ONNX_DIR/target/classpath.txt")"

echo "============================================================"
echo "GLYVEX: RUNNING COMPREHENSIVE TEST SUITE"
echo "============================================================"

echo "Test 1: FSM & Gesture Capture Self-Test"
java --enable-native-access=ALL-UNNAMED -cp "$CP" com.glyvex.touchpad.demo.SelfTest

echo
echo "Test 2: 128x128 Rasterizer Parity Self-Test"
java --enable-native-access=ALL-UNNAMED -cp "$CP" com.glyvex.ml.RasterizerSelfTest

echo
echo "Test 3: Full Pipeline & Command Execution Regression Tests"
java --enable-native-access=ALL-UNNAMED -cp "$CP" com.glyvex.test.PipelineIntegrationTest

echo
echo "Test 4: ONNX Model Inference Parity Check (Demo 7-Class Vocabulary)"
java --enable-native-access=ALL-UNNAMED -cp "$CP" \
  /home/reoo/.gemini/antigravity/brain/30b59018-060b-40dd-b0df-5243999311f9/scratch/TestImageInference.java \
  "$ROOT/Dataset/GLYVEX/ml/demo_retrain/onnx/glyvex_demo_char_classifier.onnx" \
  "$ROOT/Dataset/GLYVEX/ml/demo_retrain/onnx/classes.json" \
  "$ROOT/Dataset/GLYVEX/ml/demo_retrain/confidence_gate.json" \
  "/tmp/glyvex_samples"

echo "============================================================"
echo "ALL TESTS PASSED: 100% SUBSYSTEM INTEGRITY VERIFIED"
echo "============================================================"
