#!/usr/bin/env bash
# Run the platform Spring Boot app locally.
#
# Usage:
#   ./scripts/run-app.sh                # run normally
#   ./scripts/run-app.sh --debug        # also expose JDWP on $APP_DEBUG_PORT
#   ./scripts/run-app.sh --debug --suspend  # JDWP + suspend until debugger attaches
#   ./scripts/run-app.sh --no-deps      # skip the dev-up.sh check
#
# Reads deployment/.env so the app gets the same Mongo/Redis/Mailhog ports
# as the dev stack.

. "$(dirname "$0")/_lib.sh"

DEBUG=false
SUSPEND="n"
CHECK_DEPS=true
EXTRA_ARGS=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --debug)    DEBUG=true ;;
        --suspend)  SUSPEND="y" ;;
        --no-deps)  CHECK_DEPS=false ;;
        --)         shift; EXTRA_ARGS+=("$@"); break ;;
        *)          EXTRA_ARGS+=("$1") ;;
    esac
    shift
done

require_cmd docker
ensure_env_file
resolve_java_home

log "Java home: $JAVA_HOME"

if $CHECK_DEPS; then
    if ! docker ps --format '{{.Names}}' | grep -q '^orochiverse-mongodb$'; then
        warn "Dev stack not running — starting it first via dev-up.sh"
        "$(dirname "$0")/dev-up.sh"
    fi
fi

# Build Spring runtime properties from .env so app + infra stay in sync.
SPRING_ARGS=(
    "-Dspring.profiles.active=${SPRING_PROFILES_ACTIVE:-dev}"
    "-Dserver.port=${APP_PORT:-8080}"
    "-Dspring.data.mongodb.uri=mongodb://localhost:${MONGO_PORT:-27017}/iam_db?replicaSet=rs0&directConnection=true"
    "-Dspring.data.redis.host=localhost"
    "-Dspring.data.redis.port=${REDIS_PORT:-6379}"
    "-Dspring.mail.host=localhost"
    "-Dspring.mail.port=${MAILHOG_SMTP_PORT:-1025}"
)

JVM_ARGS=(${JAVA_OPTS:-})

if $DEBUG; then
    log "Remote debug enabled — JDWP listening on 0.0.0.0:${APP_DEBUG_PORT:-5005} (suspend=$SUSPEND)"
    JVM_ARGS+=(
        "-agentlib:jdwp=transport=dt_socket,server=y,suspend=$SUSPEND,address=*:${APP_DEBUG_PORT:-5005}"
    )
fi

cd "$PLATFORM_DIR"

log "Launching platform app on http://localhost:${APP_PORT:-8080}"
log "Press Ctrl-C to stop."
echo

# Pass JVM and Spring args via spring-boot:run's argument forwarding.
JOINED_JVM=$(IFS=' '; echo "${JVM_ARGS[*]}")
JOINED_SPRING=$(IFS=' '; echo "${SPRING_ARGS[*]}")

exec ./mvnw -B -ntp \
    spring-boot:run \
    -Dspring-boot.run.jvmArguments="$JOINED_JVM" \
    -Dspring-boot.run.arguments="$JOINED_SPRING" \
    "${EXTRA_ARGS[@]}"
