#!/usr/bin/env sh
set -eu
: "${SOCIAL_TLS_PASSWORD:?Set SOCIAL_TLS_PASSWORD (at least 6 characters)}"
[ "${#SOCIAL_TLS_PASSWORD}" -ge 6 ] || { echo 'Password needs at least 6 characters' >&2; exit 1; }
out="${2:-$(dirname "$0")/../config/tls}"
mkdir -p "$out"
[ ! -e "$out/server.p12" ] && [ ! -e "$out/truststore.p12" ] || { echo 'Stores already exist; choose another output directory' >&2; exit 1; }
san='SAN=dns:localhost,ip:127.0.0.1'
if [ -n "${1:-}" ]; then
 case "$1" in *[!A-Za-z0-9.:-]*) echo 'Invalid LAN hostname/address' >&2; exit 1;; esac
 case "$1" in *:*|[0-9]*.[0-9]*.[0-9]*.[0-9]*) san="$san,ip:$1";; *) san="$san,dns:$1";; esac
fi
keytool -genkeypair -alias social-server -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 365 -dname 'CN=Local Social Demo' -ext "$san" -ext EKU=serverAuth -storetype PKCS12 -keystore "$out/server.p12" -storepass:env SOCIAL_TLS_PASSWORD -keypass:env SOCIAL_TLS_PASSWORD
keytool -exportcert -alias social-server -keystore "$out/server.p12" -storepass:env SOCIAL_TLS_PASSWORD -file "$out/server.cer"
keytool -importcert -noprompt -alias social-server -file "$out/server.cer" -storetype PKCS12 -keystore "$out/truststore.p12" -storepass:env SOCIAL_TLS_PASSWORD
printf 'Created TLS stores in %s. Share only truststore.p12 out of band; keep server.p12 private.\n' "$out"
