#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
exec java -jar server/target/server-1.0.0-SNAPSHOT-shaded.jar server/config/social.properties "$@"
