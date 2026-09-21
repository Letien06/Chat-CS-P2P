#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
exec java -jar client/target/client-1.0.0-SNAPSHOT-shaded.jar
