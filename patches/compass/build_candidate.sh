#!/usr/bin/env bash
# patches/compass/build_candidate.sh: Isolated reproducible build entrypoint for compass candidate.
# Calls patches/compass/apply_compass.sh and delegates to scripts/build_compass_candidate.sh.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# Verify apply_compass.sh exists and is executable
"$SCRIPT_DIR/apply_compass.sh" "$ROOT/build/work/smali"

exec "$ROOT/scripts/build_compass_candidate.sh" "$@"
