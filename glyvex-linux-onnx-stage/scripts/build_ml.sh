#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$HERE"
command -v mvn >/dev/null 2>&1 || {
  echo "ERROR: Maven is required. Install with: sudo apt update && sudo apt install maven"
  exit 1
}
mvn -q -DskipTests package
echo "Built GLYVEX Java ML integration: $HERE/target/classes"
mvn -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt -Dmdep.path
