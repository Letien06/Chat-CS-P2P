#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${SOCIAL_TRUSTSTORE_PASSWORD:-}" && -f config/tls/password.local ]]; then
  export SOCIAL_TRUSTSTORE_PASSWORD="$(cat config/tls/password.local)"
fi
host="${1:-${SOCIAL_SERVER_HOST:-localhost}}"
port="${2:-${SOCIAL_SERVER_PORT:-5000}}"
truststore="${3:-${SOCIAL_TRUSTSTORE:-config/tls/truststore.p12}}"
jar='client/target/client-1.0.0-SNAPSHOT-shaded.jar'
[[ -f "$jar" ]] || { echo 'Missing client JAR. Run bash scripts/build-all.sh first.' >&2; exit 1; }
[[ "$port" =~ ^[0-9]{1,5}$ ]] && (( 10#$port >= 1 && 10#$port <= 65535 )) || { echo 'Port must be 1-65535.' >&2; exit 1; }
exec java -Dfile.encoding=UTF-8 "-Dsocial.host=$host" "-Dsocial.port=$port" "-Dsocial.truststore=$truststore" -jar "$jar"
