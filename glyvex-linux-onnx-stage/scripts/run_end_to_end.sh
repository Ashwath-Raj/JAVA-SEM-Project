#!/usr/bin/env bash
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$HERE"
PROJECT_ROOT="$(cd "$HERE/.." && pwd)"
NATIVE="$PROJECT_ROOT/glyvex-linux-touchpad/native/linux/touchpad/glyvex-touchpad"
MODEL="$PROJECT_ROOT/Dataset/GLYVEX/ml/demo_retrain/onnx/glyvex_demo_char_classifier.onnx"
CLASSES="$PROJECT_ROOT/Dataset/GLYVEX/ml/demo_retrain/onnx/classes.json"
GATE="$PROJECT_ROOT/Dataset/GLYVEX/ml/demo_retrain/confidence_gate.json"

"$HERE/scripts/build_ml.sh"

[[ -x "$NATIVE" ]] || {
  echo "ERROR: native touchpad reader not found: $NATIVE"
  echo "Run the Linux touchpad setup script first."
  exit 1
}
[[ -f "$MODEL" ]] || { echo "ERROR: model not found: $MODEL"; exit 1; }
[[ -f "$CLASSES" ]] || { echo "ERROR: classes.json not found: $CLASSES"; exit 1; }
[[ -f "$GATE" ]] || { echo "ERROR: confidence_gate.json not found: $GATE"; exit 1; }

find_touchpad() {
  local dev props name
  shopt -s nullglob
  for dev in /dev/input/event*; do
    if command -v udevadm >/dev/null 2>&1; then
      props="$(udevadm info --query=property --name="$dev" 2>/dev/null || true)"
      if grep -q '^ID_INPUT_TOUCHPAD=1$' <<<"$props"; then
        printf '%s\n' "$dev"
        shopt -u nullglob
        return 0
      fi
    fi
    name="$(cat "/sys/class/input/$(basename "$dev")/device/name" 2>/dev/null || true)"
    if grep -Eiq 'touchpad|trackpad|synaptics|elan|precision touchpad|alps' <<<"$name"; then
      printf '%s\n' "$dev"
      shopt -u nullglob
      return 0
    fi
  done
  shopt -u nullglob
  return 1
}

DEVICE="$(find_touchpad || true)"
[[ -n "$DEVICE" ]] || { echo "ERROR: touchpad device not found"; exit 1; }

echo "============================================================"
echo "GLYVEX — TOUCHPAD → FSM → RASTER → ONNX"
echo "============================================================"
echo "Touchpad: $DEVICE"
echo "Model:    $MODEL"
echo
echo "Activation: TL -> TR -> BR -> BL"
echo "Then draw B/C/E/F/L/S/T."
echo "Ctrl+C to stop."
echo

CP="$HERE/target/classes:$(cat "$HERE/target/classpath.txt")"

sudo "$NATIVE" "$DEVICE" | \
  java -cp "$CP" com.glyvex.touchpad.demo.EndToEndDemo \
    --model "$MODEL" \
    --classes "$CLASSES" \
    --gate "$GATE" \
    --debug "$HERE/runtime/debug"
