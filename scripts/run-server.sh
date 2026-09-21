#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../server"
exec java -jar target/server-1.0.0-SNAPSHOT-shaded.jar config/server.properties
