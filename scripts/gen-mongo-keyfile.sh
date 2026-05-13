#!/usr/bin/env bash
# Produces the keyFile that mongod consults for internal-cluster auth.
# Single-node rs0 still requires one when --auth is on, since mongod
# uses it to authorise its own arbiter+secondary chatter (even when
# you only have a single PRIMARY).
#
# Spec: 6–1024 base64-ish characters. We generate 512 bytes of entropy
# and base64 it. Mode 400, owned by the container's mongod uid (we
# chmod 400 here; docker bind-mounts preserve the perms).
#
# Usage:
#   scripts/gen-mongo-keyfile.sh                              # default dest
#   scripts/gen-mongo-keyfile.sh /tmp/custom/path/keyfile     # override

set -euo pipefail

OUT="${1:-deployment/prod/secrets/mongo/keyfile}"

if [[ -f "$OUT" ]]; then
  echo "Error: keyfile already exists at $OUT" >&2
  echo "Remove it explicitly if you really mean to rotate (will invalidate the existing rs0):" >&2
  echo "  rm $OUT" >&2
  exit 1
fi

mkdir -p "$(dirname "$OUT")"
openssl rand -base64 756 > "$OUT"
chmod 400 "$OUT"
echo "Generated Mongo internal-auth keyfile at $OUT (mode 400)."
