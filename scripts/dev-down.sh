#!/usr/bin/env bash
# Stop the dev stack. Volumes (Mongo data, Redis data) are preserved.
# Use `dev-reset.sh` if you want to wipe data too.

. "$(dirname "$0")/_lib.sh"
require_cmd docker
ensure_env_file

log "Stopping dev stack…"
compose down --remove-orphans
ok "Stack stopped (volumes preserved)"
