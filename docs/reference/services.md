# Services / runtime view

What runs where, on what port, with which dependencies. Read this before debugging "why isn't X reachable".

---

## Process inventory

The platform is a **single deployable Spring Boot application** (modular monolith). Everything else is infrastructure.

| Process | What it is | How it starts | Notes |
|---|---|---|---|
| `platform` (Spring Boot) | The IAM + multi-tenant API server. Single JVM, port 8080. | `./scripts/run-app.sh` (dev) or the Dockerfile (prod). | Java 25 + virtual threads (`spring.threads.virtual.enabled=true`) + ZGC. |

There is no separate auth server, gateway, or worker — by design (see `docs/architecture/01-system-overview.md`).

---

## Local Docker stack (`deployment/docker-compose.yml`)

Started by `./scripts/dev-up.sh`. Stopped by `./scripts/dev-down.sh`. Reset (drop all data) by `./scripts/dev-reset.sh`. Status by `./scripts/dev-status.sh`. Restart everything cleanly with `./scripts/dev-rerun.sh`.

| Service | Container | Host port | Image | Purpose |
|---|---|---|---|---|
| `mongodb` | `orochiverse-mongodb` | **27017** | `mongo:8.0` | Primary data store. Single-node replica set `rs0` (required for transactions). |
| `mongo-init` | (one-shot) | — | `mongo:8.0` | Initializes the replica set. Exits 0 once `rs.status()` reports PRIMARY. |
| `redis` | `orochiverse-redis` | **6379** | `redis:7.4-alpine` | Refresh-token store + login rate-limit buckets. |
| `mailhog` | `orochiverse-mailhog` | **1025** SMTP / **8025** UI | `mailhog/mailhog` | Captures outbound email locally (invites, password resets). |
| `traefik` (optional) | `orochiverse-traefik` | **80** / **8081** dashboard | `traefik:v3.x` | Reverse proxy. Enabled with `docker compose --profile proxy up`. |

### Connecting to local Mongo

- **From the host (Compass, mongosh, app):** `mongodb://localhost:27017/?replicaSet=rs0&directConnection=true`
- **From inside the container:** `docker exec -it orochiverse-mongodb mongosh`
- **DBs you'll see:** `iam_db` (shared) + one `tenant_<id>_db` per provisioned tenant.

---

## Production Docker stack (`deployment/prod/docker-compose.yml`)

The full prod compose — Traefik v3 with Let's Encrypt (TLS-ALPN-01), Mongo 8 with `--auth` + keyfile, Redis 7.4 with `requirepass`, and the `platform` container pulled from GHCR. Six commands from clone to a TLS-terminated API on a public hostname — see `docs/deployment.md` for the runbook.

| Service | Purpose | Auth |
|---|---|---|
| `traefik` | TLS termination + reverse proxy. Auto-issues a Let's Encrypt cert for `${PLATFORM_HOSTNAME}`. | — |
| `mongodb` | Single-node `rs0` with `--auth --keyFile`. Init container creates app user. | `MONGO_INITDB_ROOT_USERNAME` / `MONGO_APP_USERNAME` env. |
| `redis` | `--requirepass ${REDIS_PASSWORD}`. | `REDIS_PASSWORD`. |
| `platform` | The app image from GHCR. JWT key files mounted from `deployment/prod/secrets/jwt/`. | JWT RS256. |

`deployment/prod/.env` is the single secret file the operator fills in (template at `.env.example`). JWT keys are generated locally with `scripts/gen-jwt-keys.sh`; the Mongo rs0 keyfile is materialised automatically on first boot by the `mongo-keyfile-init` one-shot service into a docker-managed volume, so `docker compose up -d` works from a clean checkout with no pre-step. `scripts/gen-mongo-keyfile.sh` is kept as a manual escape hatch — see `docs/deployment.md`.

---

## GHCR image pipeline (`.github/workflows/`)

| Workflow | When | What it does |
|---|---|---|
| `build.yml` | Every push + PR | `./mvnw verify` against Mongo service container; uploads jar + surefire reports. |
| `release.yml` | Push to `main`, tag `v*` | Builds multi-arch image (linux/amd64 + linux/arm64) via QEMU + buildx, pushes to `ghcr.io/<owner>/<repo>/platform`. `:latest` on `main`, `:v1.2.3` on tags. |

The prod compose pulls `ghcr.io/<owner>/<repo>/platform:${PLATFORM_IMAGE_TAG:-latest}`.

---

## Spring profiles

Profile is selected via `SPRING_PROFILES_ACTIVE` (or `--spring.profiles.active=...`). Default is `dev` (`spring.profiles.default: dev` in `application.yml`).

| Profile | When | Mongo | Redis | Mail | Notes |
|---|---|---|---|---|---|
| `dev` | Local laptop | localhost:27017, `iam_db` | localhost:6379 | MailHog 1025 | Mongock runs at startup. `EphemeralRsaKeyProvider` (warns). |
| `test` | Unit tests via Surefire | **excluded** (autoconfig off) | excluded | excluded | No infra deps; pure-unit tests live here. |
| `integration` | Failsafe ITs that need Mongo | enabled, URI per-test via `@DynamicPropertySource` | optional | excluded | Mongo dev stack must be up. ITs skip when not reachable. |
| `prod` | Deployed env | `MONGODB_URI` env | `REDIS_URL` env | SMTP env | Every secret from env. `FileRsaKeyProvider` mounted from `/secrets/jwt/`. |

`UserDetailsServiceAutoConfiguration` is excluded in **every** profile (we don't want Spring's auto-generated user; auth is JWT-only).

---

## Startup order

1. **Spring context loads**, beans wire up. `@EnableMongock` triggers …
2. **Mongock** runs every `@ChangeUnit` in `common.migrations.iam` against `iam_db`. Currently: `IamBaselineIndexes` (creates indexes on `users`, `tenants`, `operator_assignments`, `audit_log`, `tenant_settings`, `single_use_tokens`).
3. **`JwtKeysConfig`** decides between `EphemeralRsaKeyProvider` (no `private-key-path` set — dev/test) or `FileRsaKeyProvider` (prod). `EphemeralRsaKeyProvider` logs a tagged WARN.
4. **`SecurityConfig`** assembles the filter chain. `RequestIdMdcFilter` + `JwtAuthenticationFilter` are inserted ahead of `UsernamePasswordAuthenticationFilter`.
5. **`BootstrapOperatorRunner`** seeds `admin@orochiverse.local` / `ChangeMe123!` if no operator exists (dev-only behavior — controlled via `platform.bootstrap.operator.*`).
6. **Tomcat** binds port 8080 (`server.port`). Smoke endpoints alive: `/actuator/health`, `/.well-known/jwks.json`, `/swagger-ui.html`.

A startup that takes longer than ~5s is suspicious — usually a Mongo connection problem; check the `dev-up.sh` stack is healthy.

---

## Public HTTP surface

Anything not listed here requires a valid Bearer JWT and runs through `JwtAuthenticationFilter`.

| Path | Method | Who | Notes |
|---|---|---|---|
| `/.well-known/jwks.json` | GET | Anyone | Public key for token verification. `Cache-Control: max-age=3600`. |
| `/actuator/health/**` | GET | Anyone | Standard Boot health. Detail visible to authenticated callers (`show-details: when-authorized`). |
| `/actuator/info` | GET | Anyone | Build/version info. |
| `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html` | GET | Anyone (in M1) | Gate or remove in prod via Traefik. |
| `/api/auth/login` | POST | Anyone | Credential entry. Rate-limited per `(email, ip)` 5/15min. |
| `/api/auth/refresh` | POST | Anyone | Refresh-token entry. |
| `/api/auth/forgot-password` | POST | Anyone | Issues single-use token, sends email. |
| `/api/auth/reset-password` | POST | Anyone | Consumes single-use token, bumps `tv`. |
| `/api/auth/accept-invite` | POST | Anyone | Activates invited user, sets password. |

Everything else — including `/actuator/prometheus` and `/actuator/metrics/**` — falls through to `.authenticated()`. The Prometheus scrape requires a Bearer token (e.g., an operator service account); lock it down at the network layer too.

---

## Tenant context flow (the load-bearing piece)

```
HTTP request
   │
   ▼
JwtAuthenticationFilter
   ├─ extract Bearer
   ├─ verify (sig + iss + exp)
   ├─ check claims.tv() == TokenVersionResolver.versionOf(userId)   [Caffeine, 30s TTL]
   ├─ build AuthenticatedUser
   ├─ SecurityContextHolder.set(auth)
   └─ if claims.activeTenantId() != null:
         TenantContext.callIn(tid, () -> chain.doFilter(...))
                        │
                        ▼
                 controller / service
                        │
                        ▼
        TenantMongoTemplateRegistry.forCurrentTenant()
                        │
                        ▼
                resolves through TenantContext
                        │
                        ▼
            tenant_<id>_db MongoTemplate (cached)
```

Controllers and services never see the JWT or compute the tenant ID — they just call `forCurrentTenant()` and the filter has already arranged for the right answer to come back.

---

## Where data lives

| Database | Owner | Collections | Indexes from |
|---|---|---|---|
| `iam_db` | `common` + `iam` | `users`, `tenants`, `operator_assignments`, `audit_log` (TTL 90d), `tenant_settings`, `single_use_tokens` (TTL on `expiresAt`), `mongockChangeLog` | `IamBaselineIndexes` |
| `tenant_<id>_db` | `iam.tenantadmin` (M1) + `gcs` (M2+) | per-tenant — created on-demand by features | `TenantDatabaseProvisioner.provision()` writes the marker doc and creates per-tenant indexes |
| Redis | `iam.auth` + `common.observability` | refresh tokens (`rt:<jti>`), login buckets (`login:<email>:<ip>`) | — |

Spring's autoconfigured `MongoTemplate` (driven by `spring.data.mongodb.uri`) targets `iam_db` only. The `TenantMongoTemplateRegistry` builds independent templates for each tenant DB on top of the same `MongoClient` connection pool.

---

## Maven goals

| Goal | What it does |
|---|---|
| `./mvnw clean install` | Full build: Spotless apply, compile, unit tests, IT tests (if Mongo reachable), package jar. |
| `./mvnw test` | 199 unit tests (Surefire). |
| `./mvnw verify` | + 113 integration tests (Failsafe). |
| `./mvnw test -DskipITs` | Skip the `*IT` classes. |
| `./mvnw spotless:apply` | Re-format imports + whitespace manually. |

Spotless runs as part of `process-sources` so a `clean install` always normalizes the code before compile — no formatting-only PR diffs.

---

## Useful dev scripts (`scripts/`)

| Script | Purpose |
|---|---|
| `dev-up.sh` | Start Mongo + Redis + MailHog via Docker. |
| `dev-down.sh` | Stop the stack (data preserved). |
| `dev-reset.sh` | Stop + drop all volumes. Use when you want a clean Mongo. |
| `dev-rerun.sh` | `dev-down` then `dev-up` — fastest way to recycle. |
| `dev-status.sh` | Print container status + health. |
| `dev-logs.sh` | `docker compose logs -f` for the stack. |
| `run-app.sh` | Resolve Java 25 home (`~/bin/jdk-25*`, `~/.jdks`, sdkman, Homebrew), then `mvnw spring-boot:run`. `--debug` enables JDWP on 5005; `--suspend` waits for debugger. |
| `gen-jwt-keys.sh` | Generate the prod RS256 keypair under `deployment/prod/secrets/jwt/` (refuses to overwrite). |
| `gen-mongo-keyfile.sh` | Generate the Mongo internal-auth keyfile (`deployment/prod/secrets/mongo/keyfile`, mode 400). |
| `_lib.sh` | Shared `resolve_java_home()` helper. |
