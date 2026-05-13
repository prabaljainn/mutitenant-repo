#!/usr/bin/env bash
# Stop the dev stack AND wipe all volumes (Mongo + Redis data).
# Use this when you want a fresh start. Asks for confirmation.

. "$(dirname "$0")/_lib.sh"
require_cmd docker
ensure_env_file

warn "This will permanently delete all Mongo + Redis data in the dev stack."
read -r -p "Type 'reset' to confirm: " answer
if [[ "$answer" != "reset" ]]; then
    log "Aborted."
    exit 0
fi

compose down --volumes --remove-orphans
ok "Dev stack stopped and volumes wiped."
