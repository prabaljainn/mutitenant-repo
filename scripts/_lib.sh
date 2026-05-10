#!/usr/bin/env bash
# Shared helpers for the dev scripts. Source from each script via:
#   . "$(dirname "$0")/_lib.sh"
# Re-runnable; never `exit` from here.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEPLOY_DIR="$REPO_ROOT/deployment"
PLATFORM_DIR="$REPO_ROOT/platform"
ENV_FILE="$DEPLOY_DIR/.env"
ENV_EXAMPLE="$DEPLOY_DIR/.env.example"

# Colors for log output (auto-disable when stdout isn't a TTY).
if [[ -t 1 ]]; then
    C_RESET=$'\e[0m'; C_BOLD=$'\e[1m'
    C_BLUE=$'\e[34m'; C_GREEN=$'\e[32m'; C_YELLOW=$'\e[33m'; C_RED=$'\e[31m'
else
    C_RESET=""; C_BOLD=""; C_BLUE=""; C_GREEN=""; C_YELLOW=""; C_RED=""
fi

log()    { printf "%s[•]%s %s\n"    "$C_BLUE"   "$C_RESET" "$*"; }
ok()     { printf "%s[✓]%s %s\n"    "$C_GREEN"  "$C_RESET" "$*"; }
warn()   { printf "%s[!]%s %s\n"    "$C_YELLOW" "$C_RESET" "$*"; }
err()    { printf "%s[✗]%s %s\n"    "$C_RED"    "$C_RESET" "$*" >&2; }

ensure_env_file() {
    if [[ ! -f "$ENV_FILE" ]]; then
        warn "deployment/.env not found — creating from .env.example"
        cp "$ENV_EXAMPLE" "$ENV_FILE"
    fi
    # shellcheck disable=SC1090
    set -a; source "$ENV_FILE"; set +a
}

require_cmd() {
    local cmd="$1"
    if ! command -v "$cmd" >/dev/null 2>&1; then
        err "$cmd not found on PATH"
        exit 1
    fi
}

resolve_java_home() {
    # 1. Honour an existing JAVA_HOME if it's valid.
    if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
        return
    fi

    # 2. Common manual install locations (user-installed JDK tarballs).
    #    Pick the highest-versioned 25.x match.
    local candidate
    for candidate in \
        "$HOME/bin"/jdk-25*/Contents/Home \
        "$HOME/bin"/jdk-25*/ \
        "$HOME"/.jdks/jdk-25*/Contents/Home \
        "$HOME"/.sdkman/candidates/java/25*/ \
        "/Library/Java/JavaVirtualMachines"/jdk-25*/Contents/Home \
        "/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home" \
        "/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home"
    do
        if [[ -x "$candidate/bin/java" ]]; then
            export JAVA_HOME="${candidate%/}"
            return
        fi
    done

    # 3. macOS java_home utility — last resort.
    if command -v /usr/libexec/java_home >/dev/null 2>&1; then
        if JH=$(/usr/libexec/java_home -v 25 2>/dev/null); then
            export JAVA_HOME="$JH"
            return
        fi
    fi

    err "Could not locate a Java 25 runtime. Install via one of:"
    err "    sdk install java 25-tem                  # SDKMAN"
    err "    brew install openjdk@25                  # Homebrew"
    err "    download JDK 25 to ~/bin/jdk-25.x/       # manual"
    err "Or export JAVA_HOME yourself before running this script."
    exit 1
}

compose() {
    docker compose -f "$DEPLOY_DIR/docker-compose.yml" --env-file "$ENV_FILE" "$@"
}
