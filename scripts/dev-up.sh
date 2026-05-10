#!/usr/bin/env bash
# Bring the local dev stack up: MongoDB 8 (replica set), Redis, Mailhog.
# Optional: pass `--profile proxy` to also start Traefik.
#
# Idempotent: re-running on an already-up stack is a no-op.

. "$(dirname "$0")/_lib.sh"

require_cmd docker
ensure_env_file

log "Starting dev stack from $DEPLOY_DIR"
log "Mongo: $MONGO_VERSION on :$MONGO_PORT  |  Redis: $REDIS_VERSION on :$REDIS_PORT  |  Mailhog UI: :$MAILHOG_UI_PORT"

compose up -d "$@"

log "Waiting for MongoDB replica set to become primary…"
deadline=$(( SECONDS + 60 ))
until compose exec -T mongodb mongosh --quiet --eval 'db.hello().isWritablePrimary' 2>/dev/null | grep -q true; do
    if (( SECONDS >= deadline )); then
        err "MongoDB did not become primary within 60s"
        compose logs mongodb mongo-init
        exit 1
    fi
    sleep 2
done

ok "MongoDB rs0 is PRIMARY"
ok "Redis is up on localhost:$REDIS_PORT"
ok "Mailhog UI: http://localhost:$MAILHOG_UI_PORT"
log "Run the platform app with: ./scripts/run-app.sh"
