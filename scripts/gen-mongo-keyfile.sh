#!/usr/bin/env bash
# Produces the keyFile that mongod consults for internal-cluster auth.
# Single-node rs0 still requires one when --auth is on, since mongod
# uses it to authorise its own arbiter+secondary chatter (even when
# you only have a single PRIMARY).
#
# Spec: 6–1024 base64-ish characters. We generate 756 bytes of entropy
# and base64 it. Mode 400.
#
# NOTE: As of the self-bootstrapping prod compose, this script is no
# longer required for `docker compose up -d` — the `mongo-keyfile-init`
# service generates the keyfile directly into the `mongodb_keyfile`
# docker volume on first boot. This helper remains as a manual escape
# hatch for operators who want to pre-seed the volume with their own
# keyfile (e.g. lifting one from an existing cluster). See
# `docs/deployment.md` → "Pre-seeding the Mongo keyfile manually" for
# the `docker run … cp` recipe that copies this file into the volume.
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
