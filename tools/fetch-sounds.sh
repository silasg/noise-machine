#!/usr/bin/env bash
# Reproduce the credited library; see --help and README.md.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
command -v python3 >/dev/null 2>&1 || { echo 'need python3 in PATH' >&2; exit 1; }
exec python3 "$SCRIPT_DIR/fetch_sounds.py" "$@"
