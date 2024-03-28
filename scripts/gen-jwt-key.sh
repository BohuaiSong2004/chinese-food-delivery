#!/usr/bin/env sh
# Writes an ES256 signing key in the layout the API reads: PKCS#8 private key followed by its public key.
# Usage: scripts/gen-jwt-key.sh [out-file]   (default: secrets/jwt.pem)
set -eu
out=${1:-secrets/jwt.pem}
mkdir -p "$(dirname "$out")"
tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$tmp"
umask 077
openssl pkcs8 -topk8 -nocrypt -in "$tmp" -out "$out"
openssl pkey -in "$tmp" -pubout >> "$out"
echo "wrote $out"
