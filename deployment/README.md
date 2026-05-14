# Deployment infrastructure

Local-dev infrastructure for the Orochiverse multi-tenant platform.
The platform application itself lives under `../platform/`.

## Stack

| Service | Image | Port | Purpose |
|---|---|---|---|
| MongoDB | `mongo:8.0` (LTS) | 27017 | Single-node replica set `rs0` for IAM + per-tenant data |
| Redis | `redis:7.4-alpine` | 6379 | Refresh tokens, login rate limits, JWT denylist |
| Mailhog | `mailhog/mailhog` | 1025 (SMTP) / 8025 (UI) | Captures invite + reset emails locally |
| Traefik | `traefik:v3.7` | 80 / 443 / 8090 | Optional — start with `--profile proxy` |

Versions are pinned in `.env` (`MONGO_VERSION`, `REDIS_VERSION`).

## Quick start

Use the runner scripts at `../scripts/` — they wrap docker-compose with
healthcheck-aware waits and sane defaults:

```bash
../scripts/dev-up.sh                  # start essentials, wait for rs0 PRIMARY
../scripts/dev-up.sh --profile proxy  # also start Traefik
../scripts/dev-status.sh              # show health + connectivity
../scripts/dev-logs.sh [service]      # tail logs
../scripts/dev-down.sh                # stop (volumes preserved)
../scripts/dev-reset.sh               # stop AND wipe volumes (asks)
```

Or raw `docker compose`:

```bash
cp .env.example .env
docker compose --env-file .env up -d
```

## Why Mongo 8.0 LTS?

- 32%+ faster reads / writes vs Mongo 7
- Dramatically improved time-series engine (relevant for drone telemetry in M2+)
- LTS support window: ~Oct 2027
- Override to a rapid-release line via `MONGO_VERSION=8.3` in `.env` if you
  want bleeding-edge — at the cost of a 6-month support window.

## Replica set in dev

Mongo runs in single-node `rs0` mode because Spring Data Mongo's
multi-document transactions (used during tenant onboarding in Phase 1.7+)
require a replica set. The `mongo-init` one-shot service initiates `rs0`
on first boot — idempotent on subsequent runs.

## Auth model

Dev mode runs Mongo **without authentication** for simplicity. Production
deployments should:

1. Enable `--auth` and a proper keyfile or x.509 for inter-replica auth
2. Inject `MONGODB_URI` with credentials via env var (`SPRING_PROFILES_ACTIVE=prod`)
3. Not use this docker-compose at all — use Mongo Atlas or a managed cluster

See `../docs/superpowers/specs/2026-05-11-platform-shell-m1-design.md` §8
for production security policy.

## Indexes / schema

Owned by the Spring Boot app via Mongock changesets (Phase 1.4+), **not**
by SQL/JS init scripts here. See `mongodb/init-scripts/README.md`.

## Data persistence

| Volume | Contents |
|---|---|
| `mongodb_data` | `iam_db` + per-tenant DBs |
| `mongodb_config` | replica set config |
| `redis_data` | RDB snapshots + AOF |

`dev-reset.sh` is the only script that nukes these.
