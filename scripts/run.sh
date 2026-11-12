#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

NATIVE="$ROOT/glyvex-linux-touchpad/native/linux/touchpad/glyvex-touchpad"
CONFIG="$ROOT/config/glyvex-config.json"
ONNX_DIR="$ROOT/glyvex-linux-onnx-stage"
CP="$ONNX_DIR/target/classes:$(cat "$ONNX_DIR/target/classpath.txt")"

# 1. Compile C binary if missing
if [[ ! -x "$NATIVE" ]]; then
  echo "[GLYVEX] Compiling native touchpad reader..."
  gcc -Wall -Wextra -O2 "$ROOT/glyvex-linux-touchpad/native/linux/touchpad/glyvex_touchpad.c" -o "$NATIVE"
fi

# 2. Detect touchpad device
find_touchpad() {
  local dev name props
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
if [[ -z "$DEVICE" ]]; then
  echo "ERROR: Touchpad device not found in /dev/input/event*."
  echo "You can still run in simulation mode with: ./scripts/simulate.sh C"
  exit 1
fi

echo "============================================================"
echo "GLYVEX: STARTING ON PHYSICAL TOUCHPAD ($DEVICE)"
echo "============================================================"
echo "Activation: Tap Top-Left -> Top-Right -> Bottom-Right -> Bottom-Left"
echo "Draw:       B (Browser) | C (Calculator) | F (Files) | T (Terminal) | E (Editor)"
echo "Press Ctrl+C to terminate."
echo "============================================================"
echo

# If user is in group 'input' or device is readable, run directly; otherwise use sudo for the native reader
if [[ -r "$DEVICE" ]]; then
  "$NATIVE" "$DEVICE" | \
    java --enable-native-access=ALL-UNNAMED \
      -cp "$CP" com.glyvex.app.GlyvexApplication \
      --config "$CONFIG" \
      --root "$ROOT" \
      "$@"
else
  echo "[GLYVEX] Device requires elevated privileges. Prompting for sudo for reader only..."
  sudo "$NATIVE" "$DEVICE" | \
    java --enable-native-access=ALL-UNNAMED \
      -cp "$CP" com.glyvex.app.GlyvexApplication \
      --config "$CONFIG" \
      --root "$ROOT" \
      "$@"
fi
