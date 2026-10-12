#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${SOCIAL_TLS_PASSWORD:-}" && -f config/tls/password.local ]]; then
  export SOCIAL_TLS_PASSWORD="$(cat config/tls/password.local)"
fi
exec java -jar server/target/server-1.0.0-SNAPSHOT-shaded.jar server/config/social.properties "$@"
