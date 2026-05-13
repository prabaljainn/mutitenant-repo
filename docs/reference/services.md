# Services / runtime view

What runs where, on what port, with which dependencies. Read this before debugging "why isn't X reachable".

---

## Process inventory

The platform is a **single deployable Spring Boot application** (modular monolith). Everything else is infrastructure.

| Process | What it is | How it starts | Notes |
|---|---|---|---|
| `platform` (Spring Boot) | The IAM + multi-tenant API server. Single JVM, port 8080. | `./scripts/run-app.sh` (dev) or the Dockerfile (prod). | Java 25 + virtual threads enabled (`spring.threads.virtual.enabled=true`). |

That's it. There is no separate auth server, gateway, or worker — by design (see `docs/architecture/01-system-overview.md`).

---

## Local Docker stack (`deployment/docker-compose.yml`)

Started by `./scripts/dev-up.sh`. Stopped by `./scripts/dev-down.sh`. Reset (drop all data) by `./scripts/dev-reset.sh`. Status by `./scripts/dev-status.sh`.

| Service | Container | Host port | Image | Purpose |
|---|---|---|---|---|
| `mongodb` | `orochiverse-mongodb` | **27017** | `mongo:8.0` | Primary data store. Single-node replica set `rs0` (required for transactions). |
| `mongo-init` | (one-shot) | — | `mongo:8.0` | Initializes the replica set. Exits 0 once `rs.status()` reports PRIMARY. |
| `redis` | `orochiverse-redis` | **6379** | `redis:7.4-alpine` | Refresh-token store, login rate-limiter (Phase 1.10). Not yet wired in M1. |
| `mailhog` | `orochiverse-mailhog` | **1025** SMTP / **8025** UI | `mailhog/mailhog` | Captures outbound email locally (Phase 1.9). |
| `traefik` (optional) | `orochiverse-traefik` | **80** / **8081** dashboard | `traefik:v3.x` | Reverse proxy. Enabled with `docker compose --profile proxy up`. |

### Connecting to local Mongo

- **From the host (Compass, mongosh, app):** `mongodb://localhost:27017/?replicaSet=rs0&directConnection=true`
- **From inside the container:** `docker exec -it orochiverse-mongodb mongosh`
- **DBs you'll see:** `iam_db` (shared) + one `tenant_<id>_db` per provisioned tenant.

---

## Spring profiles

Profile is selected via `SPRING_PROFILES_ACTIVE` (or `--spring.profiles.active=...`). Default is `dev` (`spring.profiles.default: dev` in `application.yml`).

| Profile | When | Mongo | Redis | Mail | Notes |
|---|---|---|---|---|---|
| `dev` | Local laptop | localhost:27017, `iam_db` | localhost:6379 (when wired) | MailHog 1025 | Mongock runs at startup. |
| `test` | Unit tests via Surefire | **excluded** (autoconfig off) | excluded | excluded | No infra deps; smoke + pure-unit tests live here. |
| `integration` | Failsafe ITs that need Mongo | enabled, URI per-test via `@DynamicPropertySource` | excluded | excluded | Mongo dev stack must be up. |
| `prod` | Deployed env | `MONGODB_URI` env | `REDIS_URL` env | SMTP env | Every secret comes from env; nothing in repo. |

`UserDetailsServiceAutoConfiguration` is excluded in **every** profile (we don't want Spring's auto-generated user; auth is JWT-only).

---

## Startup order

1. **Spring context loads**, beans wire up. `@EnableMongock` triggers …
2. **Mongock** runs every `@ChangeUnit` in `common.migrations.iam` against `iam_db`. Currently: `IamBaselineIndexes` (creates indexes on `users`, `tenants`, `operator_assignments`, `audit_log`).
3. **`JwtKeysConfig`** decides between `EphemeralRsaKeyProvider` (no `private-key-path` set — dev/test) or `FileRsaKeyProvider` (prod). `EphemeralRsaKeyProvider` logs a tagged WARN.
4. **`SecurityConfig`** assembles the filter chain. `JwtAuthenticationFilter` is inserted ahead of `UsernamePasswordAuthenticationFilter`.
5. **Tomcat** binds port 8080 (`server.port`). Smoke endpoints alive: `/actuator/health`, `/.well-known/jwks.json`.

A startup that takes longer than ~5s is suspicious — usually a Mongo connection problem; check the `dev-up.sh` stack is healthy.

---

## Public HTTP surface

Anything not listed here requires a valid Bearer JWT and runs through `JwtAuthenticationFilter`.

| Path | Method | Who | Notes |
|---|---|---|---|
| `/.well-known/jwks.json` | GET | Anyone | Public key for token verification. `Cache-Control: max-age=3600`. |
| `/actuator/health/**` | GET | Anyone | Standard Boot health. Detail visible to authenticated callers (`show-details: when-authorized`). |
| `/actuator/info` | GET | Anyone | Build/version info. |
| `/actuator/prometheus` | GET | Anyone | Metrics scrape (Phase 1.10). Lock down via network policy in prod. |
| `/actuator/metrics/**` | GET | Anyone | Same. |
| `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html` | GET | Anyone (in M1) | Gate or remove in prod via Traefik. |
| `/api/auth/login` | POST | Anyone | Phase 1.7 — credential-bearing entry point. |
| `/api/auth/refresh` | POST | Anyone | Phase 1.7 — refresh-token-bearing entry point. |
| `/api/auth/forgot-password` | POST | Anyone | Phase 1.7. |
| `/api/auth/reset-password` | POST | Anyone | Phase 1.7. |
| `/api/auth/me` | GET | Authenticated | Returns the current principal. Useful for client-side token introspection. |

Phase 1.7 (operator admin), 1.8 (tenant admin), and beyond add more endpoints behind `.authenticated()`.

---

## Tenant context flow (the load-bearing piece)

```
HTTP request
   │
   ▼
JwtAuthenticationFilter
   ├─ extract Bearer
   ├─ verify (sig + iss + exp)
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

The point is that the controller and service layers never see the JWT or compute the tenant ID — they just call `forCurrentTenant()` and the filter has already arranged for the right answer to come back.

---

## Where data lives

| Database | Owner | Collections | Indexes from |
|---|---|---|---|
| `iam_db` | `common` + `iam` | `users`, `tenants`, `operator_assignments`, `audit_log`, `mongockChangeLog` | `IamBaselineIndexes` |
| `tenant_<id>_db` | `tenant` (M1) + `gcs` (M2+) | per-tenant — created on-demand by features | `TenantDatabaseProvisioner.provision()` writes the marker doc and creates per-tenant indexes |

Spring's autoconfigured `MongoTemplate` (driven by `spring.data.mongodb.uri`) targets `iam_db` only. The `TenantMongoTemplateRegistry` builds independent templates for each tenant DB on top of the same `MongoClient` connection pool.

---

## Maven goals

| Goal | What it does |
|---|---|
| `./mvnw clean install` | Full build: Spotless apply, compile, unit tests, IT tests (if Mongo reachable), package jar. |
| `./mvnw test` | Unit tests only (Surefire). |
| `./mvnw verify` | Unit + integration tests (Failsafe). |
| `./mvnw test -DskipITs` | Skip the `*IT` classes. |
| `./mvnw spotless:apply` | Re-format imports + whitespace manually. |

Spotless runs as part of `process-sources` so a `clean install` always normalizes the code before compile — no formatting-only PR diffs.

---

## Useful dev scripts (`scripts/`)

| Script | Purpose |
|---|---|
| `dev-up.sh` | Start Mongo + (optionally) Redis + MailHog via Docker. |
| `dev-down.sh` | Stop the stack (data preserved). |
| `dev-reset.sh` | Stop + drop all volumes. Use when you want a clean Mongo. |
| `dev-status.sh` | Print container status + health. |
| `dev-logs.sh` | `docker compose logs -f` for the stack. |
| `run-app.sh` | Resolve Java 25 home (`~/bin/jdk-25*`, `~/.jdks`, sdkman, Homebrew), then `mvnw spring-boot:run`. `--debug` enables JDWP on 5005. |
| `_lib.sh` | Shared `resolve_java_home()` helper. |
