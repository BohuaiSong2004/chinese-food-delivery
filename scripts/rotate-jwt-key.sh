#!/usr/bin/env sh
# Starts a JWT signing-key rotation (see docs/operations.md).
#   1. appends the current public key to the retired-keys file
#   2. writes a fresh signing key in place of the current one (the old file is kept as <key>.old)
# Deploy with JWT_PREVIOUS_PUBLIC_KEYS_FILE pointing at the retired-keys file; after one access-token
# lifetime (15 min by default) the retired key can be removed from that file.
# Usage: scripts/rotate-jwt-key.sh [key-file] [retired-file]   (defaults: secrets/jwt.pem secrets/jwt-retired.pem)
set -eu
key=${1:-secrets/jwt.pem}
retired=${2:-secrets/jwt-retired.pem}
[ -f "$key" ] || { echo "no key at $key" >&2; exit 1; }
umask 077
openssl pkey -in "$key" -pubout >> "$retired"
cp "$key" "$key.old"
"$(dirname "$0")/gen-jwt-key.sh" "$key.new" >/dev/null
mv "$key.new" "$key"
echo "rotated $key (old key kept at $key.old); retired public keys: $retired"
