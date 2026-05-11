#!/usr/bin/env bash
# Tear down and bring back up: kills any running platform process bound
# to APP_PORT, restarts the Docker dev stack (Mongo / Redis / Mailhog),
# and re-runs the Spring Boot app.
#
# Usage:
#   ./scripts/dev-rerun.sh                 # soft rerun (preserves Mongo data)
#   ./scripts/dev-rerun.sh --reset         # also wipe Docker volumes (drops iam_db + tenant DBs)
#   ./scripts/dev-rerun.sh --debug         # JDWP on $APP_DEBUG_PORT
#   ./scripts/dev-rerun.sh --no-app        # only restart infra; don't launch the app
#   ./scripts/dev-rerun.sh --reset --debug # combine
#
# Anything after `--` is forwarded verbatim to run-app.sh.

. "$(dirname "$0")/_lib.sh"

RESET=false
DEBUG=false
RUN_APP=true
APP_FORWARD=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --reset)   RESET=true ;;
        --debug)   DEBUG=true ;;
        --no-app)  RUN_APP=false ;;
        -h|--help)
            sed -n '1,/^$/p' "$0" | sed 's/^# \?//'
            exit 0 ;;
        --)        shift; APP_FORWARD+=("$@"); break ;;
        *)         APP_FORWARD+=("$1") ;;
    esac
    shift
done

require_cmd docker
ensure_env_file

# ──────────────────────────────────────────────────────────────────────
# 1. Kill any platform process holding APP_PORT.
# ──────────────────────────────────────────────────────────────────────
APP_PORT="${APP_PORT:-8080}"
if command -v lsof >/dev/null 2>&1; then
    # -ti = terse + ids only; the trailing kill is a no-op if the list is empty.
    PIDS="$(lsof -ti tcp:"$APP_PORT" -sTCP:LISTEN 2>/dev/null || true)"
    if [[ -n "$PIDS" ]]; then
        warn "Killing process(es) on :$APP_PORT — pids: $(tr '\n' ' ' <<<"$PIDS")"
        # SIGTERM first so Spring's graceful shutdown drains; SIGKILL after 5s.
        kill $PIDS 2>/dev/null || true
        for _ in 1 2 3 4 5; do
            sleep 1
            STILL="$(lsof -ti tcp:"$APP_PORT" -sTCP:LISTEN 2>/dev/null || true)"
            [[ -z "$STILL" ]] && break
        done
        STILL="$(lsof -ti tcp:"$APP_PORT" -sTCP:LISTEN 2>/dev/null || true)"
        if [[ -n "$STILL" ]]; then
            warn "Process still up after SIGTERM — sending SIGKILL"
            kill -9 $STILL 2>/dev/null || true
        fi
        ok "Port :$APP_PORT freed"
    else
        log "Port :$APP_PORT is already free"
    fi
else
    warn "lsof not available — skipping port-cleanup step"
fi

# ──────────────────────────────────────────────────────────────────────
# 2. Tear down the Docker stack.
# ──────────────────────────────────────────────────────────────────────
if $RESET; then
    log "Stopping stack AND dropping volumes (--reset)"
    compose down --remove-orphans -v
    ok "Stack down + volumes wiped (Mongo iam_db + tenant DBs are gone)"
else
    log "Stopping stack (volumes preserved)"
    compose down --remove-orphans
    ok "Stack down"
fi

# ──────────────────────────────────────────────────────────────────────
# 3. Bring the stack back up.
# ──────────────────────────────────────────────────────────────────────
"$(dirname "$0")/dev-up.sh"

# ──────────────────────────────────────────────────────────────────────
# 4. Run the app (foreground, Ctrl-C to stop).
# ──────────────────────────────────────────────────────────────────────
if ! $RUN_APP; then
    ok "Stack rerun complete. App not started (--no-app)."
    log "Start it later with: ./scripts/run-app.sh"
    exit 0
fi

RUN_ARGS=()
$DEBUG && RUN_ARGS+=(--debug)
RUN_ARGS+=(--no-deps)  # we just brought infra up — skip the redundant check
RUN_ARGS+=("${APP_FORWARD[@]}")

log "Launching platform app…"
exec "$(dirname "$0")/run-app.sh" "${RUN_ARGS[@]}"
