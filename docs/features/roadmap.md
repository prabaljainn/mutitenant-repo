# Roadmap

Where the platform is, and what's next. Status reflects what's on `main`.

---

## ✅ M1 — Platform shell (DONE)

The IAM + multi-tenancy foundation. Operators can stand up tenants and invite
users; tenant admins can manage their own users; integrations (MQTT, DJI) are
configurable per tenant. Production deployable.

| # | Phase | Status | What landed |
|---|---|---|---|
| 1.1 | Repo & build skeleton | ✅ | Maven multi-module-style packaging, Spotless, Java 25, GH Actions test pipeline. |
| 1.2 | Mongo 8 dev env + Spotless + runner scripts | ✅ | `scripts/dev-*.sh`, `MongoConnectivityIT`. |
| 1.3 | Multi-tenant Mongo wiring | ✅ | `TenantContext` (Java 25 `ScopedValue`), `TenantMongoTemplateRegistry`, `TenantDatabaseProvisioner`, per-tenant DB. |
| 1.4 | IAM data model + Mongock | ✅ | `User`, `Tenant`, `OperatorAssignment`, `AuditEntry`, baseline indexes via `IamBaselineIndexes`. |
| 1.5 | JWT / JWKS / BCrypt | ✅ | `AccessTokenIssuer`/`Verifier`, `JwksController`, `PasswordHashing` (cost 12), file + ephemeral key providers. |
| 1.6 | Security filter chain | ✅ | `JwtAuthenticationFilter` binds `SecurityContext` and `TenantContext` per request; `@EnableMethodSecurity`. |
| 1.7a | Auth APIs | ✅ | `/api/auth/{login,refresh,logout,switch-tenant,me}`. |
| 1.7b | Operator admin APIs | ✅ | `/admin/api/{tenants,operators,operators/{id}/assignments,audit}`. |
| 1.8 | Tenant-admin self-service | ✅ | `/api/tenant/{me,users}` — TENANT_OWNER + ADMIN write, every role reads. |
| 1.9 | Email service + invite-accept + password reset | ✅ | Thymeleaf templates, SMTP, single-use tokens, MailHog for dev capture. |
| 1.10 | Observability + auth hardening | ✅ | `RequestIdMdcFilter`, `LoginRateLimiter`, `TokenVersionLookup`+`Resolver` (Caffeine), `AuthMetrics`. |
| 1.11 | Test fixtures + JWT test support | ✅ | `testsupport/` package (`IT`, `IamFixtures`, `JwtTestSupport`, `MongoTestSupport`) — replaced `AdminItSupport`. |
| 1.12 | CI + Dockerfile + OpenAPI hardening | ✅ | `.github/workflows/build.yml`, HEALTHCHECK + non-root in Dockerfile, `@Tag` on every controller. |

### Gap fills (post-1.12, before deploy)

The CloudGCS admin-console prototype review surfaced four missing APIs and six review findings. All landed on `main`:

| Gap / Fix | What | Status |
|---|---|---|
| `GET /admin/api/stats/overview` | One-shot dashboard counters (tenants, tenantUsers, pendingInvites). | ✅ |
| `?q=` on `/admin/api/tenants` | Server-side case-insensitive substring search. | ✅ |
| `/admin/api/tenants/{id}/users/*` | Admin-side tenant-user CRUD without `switch-tenant` dance. | ✅ |
| `/admin/api/tenants/{id}/settings/{kind}` | Extensible per-tenant settings store (MQTT, DJI, …). | ✅ |
| `/api/tenant/settings/{kind}` | Tenant-side read view (OWNER + ADMIN). | ✅ |
| `tv` bump on suspend / role-change / delete | Access tokens revoked immediately, not at TTL expiry. | ✅ |
| `/actuator/prometheus` no longer public | Falls through to `.authenticated()`. | ✅ |
| SSRF guard in `ConnectionTester` | Refuses loopback / link-local / RFC1918 / CGNAT / IPv6 ULA. | ✅ |
| `try/finally` on settings cleanup | Audit always lands even if cleanup blips. | ✅ |
| ConnectionTester success-path tests | 12 new unit tests. | ✅ |
| Mongo grant + 4 limits documented | "Known limitations" section in `docs/deployment.md`. | ✅ |

### Deployment

| | Status |
|---|---|
| Multi-arch GHCR image pipeline (amd64 + arm64) | ✅ `.github/workflows/release.yml` |
| Production docker-compose (Traefik + Let's Encrypt + Mongo auth + Redis auth) | ✅ `deployment/prod/` |
| JWT keypair + Mongo keyfile generators | ✅ `scripts/gen-jwt-keys.sh`, `scripts/gen-mongo-keyfile.sh` |
| End-to-end deployment doc | ✅ `docs/deployment.md` |

---

## 🛠 M1.5 — Frontend (NOT STARTED)

The admin console prototype is in `docs/postman/` and the prototype HTML
bundle. Implementing the React SPA against the M1 API.

| # | Feature | Priority |
|---|---|---|
| F-100 | Scaffold `admin-console/` React project | P0 |
| F-101 | Login + token refresh + logout | P0 |
| F-102 | Dashboard (Overview with `/admin/api/stats`) | P0 |
| F-103 | Tenants list + create + detail | P0 |
| F-104 | Tenant detail → Members tab (admin-side CRUD) | P0 |
| F-105 | Tenant detail → Settings tab (MQTT + DJI forms) | P0 |
| F-106 | Operators list + invite | P1 |
| F-107 | Audit log viewer | P1 |
| F-108 | Tenant-facing SPA (separate project) | P2 |

---

## 🚀 M2 — Drone domain (NOT STARTED)

The first real feature module. Lives in `gcs/` which is currently a
placeholder. Module-boundary tests already enforce that `gcs.*` must use
`TenantMongoTemplateRegistry.forCurrentTenant()` and store everything in
`tenant_<id>_db`.

| # | Feature | Priority |
|---|---|---|
| F-200 | Drone fleet (Normal + DJI) | P0 |
| F-201 | Drone telemetry ingestion (MQTT, uses tenant's broker settings) | P0 |
| F-202 | Saved locations | P1 |
| F-203 | Missions (plan, queue, execute) | P0 |
| F-204 | Mission console (live telemetry + commands) | P1 |
| F-205 | DJI Cloud integration (uses tenant's DJI settings) | P1 |

---

## 🔐 M3+ — Hardening (NOT STARTED)

Picked up from the deploy-time "known limitations" list in `docs/deployment.md`.

| # | Item | Why | Priority |
|---|---|---|---|
| F-300 | JWT keypair hot rotation | Currently "stop, regenerate, restart"; 15-minute outage at worst. | P1 |
| F-301 | Redis-backed `TokenVersionLookup` cache | Per-instance Caffeine cache only works single-node. | P2 |
| F-302 | Per-email rate-limit bucket | Today's `(email, ip)` bucket is defeated by IP rotation. | P2 |
| F-303 | Mongo app-user grant tightening | Replace `readWriteAnyDatabase` with a per-tenant role granted by the provisioner. | P2 |
| F-304 | MFA (TOTP) | Optional per-user. | P3 |
| F-305 | OAuth2 / SAML | When the first customer asks for SSO. | P3 |
| F-306 | API keys for programmatic access | Out of scope until external integrations land. | P3 |
| F-307 | Tenant-side write on integration settings | Today operators set this up; self-service is a UX call. | P3 |

---

## Priority legend

| | Meaning |
|---|---|
| P0 | Must have — blocks the milestone. |
| P1 | Should have — first usable version. |
| P2 | Nice to have — second iteration. |
| P3 | Future — planned but not urgent. |
