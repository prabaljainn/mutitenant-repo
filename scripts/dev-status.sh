#!/usr/bin/env bash
# Show status of the dev stack and quick connectivity checks.

. "$(dirname "$0")/_lib.sh"
require_cmd docker
ensure_env_file

log "Container status:"
compose ps

echo
log "Quick connectivity checks:"

if compose exec -T mongodb mongosh --quiet --eval 'db.hello().isWritablePrimary' 2>/dev/null | grep -q true; then
    ok "MongoDB rs0 PRIMARY  (mongodb://localhost:$MONGO_PORT/?replicaSet=rs0)"
else
    err "MongoDB not primary"
fi

if compose exec -T redis redis-cli ping 2>/dev/null | grep -q PONG; then
    ok "Redis OK            (redis://localhost:$REDIS_PORT)"
else
    err "Redis not responding"
fi

if curl -sf "http://localhost:$MAILHOG_UI_PORT/api/v2/messages" >/dev/null 2>&1; then
    ok "Mailhog UI OK       (http://localhost:$MAILHOG_UI_PORT)"
else
    warn "Mailhog UI not reachable"
fi
