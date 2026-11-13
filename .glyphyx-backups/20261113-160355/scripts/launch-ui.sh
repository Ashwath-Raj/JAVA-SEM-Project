#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ONNX_DIR="$ROOT/glyvex-linux-onnx-stage"
CP_FILE="$ONNX_DIR/target/classpath.txt"
if [[ -z "${DISPLAY:-}" && -z "${WAYLAND_DISPLAY:-}" ]]; then
  echo "ERROR: No graphical Linux session detected (DISPLAY/WAYLAND_DISPLAY is empty)." >&2
  exit 1
fi
if [[ ! -s "$CP_FILE" || ! -f "$ONNX_DIR/target/classes/com/glyvex/ui/GlyvexDesktopLauncher.class" ]]; then
  echo "GLYVEX UI is not built yet. Running the project build..."
  "$ROOT/scripts/build.sh"
fi
CP="$ONNX_DIR/target/classes:$(cat "$CP_FILE")"
exec java --enable-native-access=ALL-UNNAMED -Dglyvex.root="$ROOT" -cp "$CP" com.glyvex.ui.GlyvexDesktopLauncher
