#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_ROOT="$(cd "$ROOT/.." && pwd)"
TOUCHPAD_DIR="$PROJECT_ROOT/glyvex-linux-touchpad/native/linux/touchpad"
DEVICE="${1:-}"

"$ROOT/scripts/build_java.sh"

if [[ -z "$DEVICE" ]]; then
    if [[ -x "$TOUCHPAD_DIR/detect_touchpad.sh" ]]; then
        DEVICE="$("$TOUCHPAD_DIR/detect_touchpad.sh" | head -n1)"
    fi
fi

if [[ -z "$DEVICE" ]]; then
    echo "ERROR: touchpad device not found"
    exit 1
fi

if [[ ! -x "$TOUCHPAD_DIR/glyvex-touchpad" ]]; then
    echo "ERROR: native helper missing: $TOUCHPAD_DIR/glyvex-touchpad"
    echo "Run the Linux touchpad setup script first."
    exit 1
fi

# The native helper requires access to /dev/input/eventX on typical Ubuntu installs.
# We use sudo for the native process only; Java remains unprivileged.
echo "Device: $DEVICE"
echo "Starting: native touchpad -> Java FSM -> gesture capture"
echo
echo "Activation sequence: TOP_LEFT -> TOP_RIGHT -> BOTTOM_RIGHT -> BOTTOM_LEFT"
echo "Release between each corner. After activation, draw the character."
echo

sudo "$TOUCHPAD_DIR/glyvex-touchpad" "$DEVICE" \
  | java -cp "$ROOT/out" com.glyvex.touchpad.demo.TouchpadConsoleDemo
