#!/usr/bin/env bash
# Signs each engine zip with the app's release key: writes <zip>.sig, an RSA
# SHA-256 signature the app checks against its own signing certificate.
#
# Usage: engine/sign.sh KEYSTORE PASSWORD zip...      (alias "ursa", PKCS12 keystore)
set -euo pipefail
KS=$1; PASS=$2; shift 2
KEY=$(mktemp)
trap 'rm -f "$KEY"' EXIT
openssl pkcs12 -in "$KS" -nocerts -nodes -passin "pass:$PASS" 2>/dev/null | openssl pkey -out "$KEY"
for z in "$@"; do
  openssl dgst -sha256 -sign "$KEY" -out "$z.sig" "$z"
  echo "signed $(basename "$z")"
done
