#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
host="${1:-${SOCIAL_SERVER_HOST:-}}"
port="${2:-5000}"
if [[ -z "$host" ]]; then read -r -p 'IP/hostname of Windows server: ' host; fi
[[ -n "$host" ]] || { echo 'Server host is required.' >&2; exit 1; }
exec bash scripts/run-client.sh "$host" "$port"
