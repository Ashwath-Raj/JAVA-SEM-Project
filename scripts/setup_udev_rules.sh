#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RULE_FILE="$ROOT/config/99-glyvex-touchpad.rules"

echo "============================================================"
echo "GLYVEX: PASSWORDLESS TOUCHPAD PERMISSIONS HELPER"
echo "============================================================"
echo "To allow GLYVEX to read /dev/input without sudo, a udev rule"
echo "can grant read permissions to the 'input' user group."
echo

mkdir -p "$ROOT/config"
cat <<'EOF' > "$RULE_FILE"
# GLYVEX Touchpad Permissions Rule
# Grants read/write access to touchpads for members of the 'input' group
KERNEL=="event*", SUBSYSTEM=="input", ATTRS{name}=="*Touchpad*", MODE="0660", GROUP="input"
KERNEL=="event*", SUBSYSTEM=="input", ATTRS{name}=="*touchpad*", MODE="0660", GROUP="input"
KERNEL=="event*", SUBSYSTEM=="input", ATTRS{name}=="*Synaptics*", MODE="0660", GROUP="input"
KERNEL=="event*", SUBSYSTEM=="input", ATTRS{name}=="*ELAN*", MODE="0660", GROUP="input"
KERNEL=="event*", SUBSYSTEM=="input", ATTRS{name}=="*ALPS*", MODE="0660", GROUP="input"
EOF

echo "Generated udev rule at: $RULE_FILE"
echo
echo "To install it on your laptop (optional), run:"
echo "  sudo cp \"$RULE_FILE\" /etc/udev/rules.d/"
echo "  sudo usermod -aG input \"\$USER\""
echo "  sudo udevadm control --reload-rules && sudo udevadm trigger"
echo
echo "Then log out and back in to apply group changes."
echo "============================================================"
