#!/usr/bin/env bash
# Tail logs from the dev stack. Pass a service name to scope:
#   ./scripts/dev-logs.sh             # all services
#   ./scripts/dev-logs.sh mongodb     # just MongoDB

. "$(dirname "$0")/_lib.sh"
require_cmd docker
ensure_env_file

compose logs -f --tail=100 "$@"
