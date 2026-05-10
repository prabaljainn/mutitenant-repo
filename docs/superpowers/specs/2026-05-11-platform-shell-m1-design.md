# Platform Shell — Milestone 1 Design Spec

**Date:** 2026-05-11
**Status:** Approved
**Owner:** prabal@orochiverse.com

---

## 1. Context

SoftBank operates a multi-tenant ground-control / drone-management platform serving five customer organizations. The existing GCS code is a single-tenant Spring Boot application of mixed quality. This project replaces that with a fresh, multi-tenant platform shell into which the GCS will be migrated incrementally (strangler-fig).

This spec covers **Milestone 1**: the foundation — IAM, multi-tenant data plumbing, JWT auth, and the admin APIs needed to onboard tenants and users. It does **not** cover GCS feature migration (M2+).

---

## 2. Locked architectural decisions

| Decision | Choice | Rationale |
|---|---|---|
| Operator model | **Single operator (SoftBank only)**, no `OperatorOrg` entity — implicit | No reseller plans; avoids unnecessary entity |
| Operator scope model | **Two operator tiers + uniform role across assigned tenants** | `OPERATOR_ADMIN` / `OPERATOR_SUPPORT`; assignment is a flat tenant list |
| Repo & service shape | **Modular monolith**, single deployable Spring Boot app | Faster iteration; modules can be extracted later behind JWT contract |
| Frontend | **One admin SPA, role-aware views** | Operator and tenant-admin share codebase; views switch by role |
| Build vs buy IAM | **Build from scratch** | Operator-tier model fits naturally; no third-party complexity |
| Backend stack | **Spring Boot 3.5+ on Java 25 LTS** | Team fluency; existing GCS migrates Java→Java; virtual threads close the perf gap |
| Frontend stack | **Angular** (modernize incrementally) | Team fluency; matches existing GCS UI |
| Database | **MongoDB 7** with shared `iam_db` + per-tenant `tenant_<id>_db` | Single engine; physical tenant isolation; Mongo TimeSeries for telemetry later |
| Cache / sessions | **Redis 7** | Refresh tokens, denylist, rate limits |
| Token model | **JWT (RS256) + JWKS endpoint** | Stateless; GCS and future products verify locally |
| Tenant user multi-tenancy | **Strictly siloed** — only operator users are cross-tenant | Simpler model; matches B2B drone-ops reality |
| V1 onboarding | **Invite-only**; no public signup | B2B norm |
| V1 auth features | **Email + password + reset only** | MFA / SSO deferred to V2 with extension points |
| Concurrency primitive | **Virtual threads + Scoped Values** for tenant context | Java 25 native; no `ThreadLocal` leak risk |

---

## 3. System boundaries

```
                   ┌──────────────────────────────────────┐
                   │         Admin SPA (Angular)           │
                   │  Operator views + Tenant-admin views  │
                   └──────────────────┬───────────────────┘
                                      │ HTTPS
                                      ▼
┌──────────────────────────────────────────────────────────┐
│            Spring Boot 3.5 / Java 25 (modular monolith)   │
│                                                           │
│   ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌──────────┐   │
│   │ common/ │  │  iam/   │  │ tenant/ │  │  gcs/    │   │
│   │ (shared)│  │(operator│  │(tenant- │  │  (M2+)   │   │
│   │         │  │  CRUD)  │  │  admin) │  │          │   │
│   └─────────┘  └─────────┘  └─────────┘  └──────────┘   │
│                                                           │
│  Filters: JwtAuthFilter → TenantContextFilter            │
│  Authorization: @PreAuthorize + tenant-scope checks      │
└────────┬───────────────────┬──────────────────┬─────────┘
         │                   │                  │
         ▼                   ▼                  ▼
   ┌──────────┐         ┌─────────┐        ┌─────────┐
   │ MongoDB  │         │  Redis  │        │  SMTP   │
   │  iam_db  │         │ tokens  │        │(Mailhog │
   │ tenant_*_│         │ denylist│        │  / SES) │
   │   db     │         │ rate    │        │         │
   └──────────┘         └─────────┘        └─────────┘
```

### What this milestone owns
- Identity (users, password hashing, email verification, reset)
- Tenancy (tenants table; lifecycle TRIAL → ACTIVE → SUSPENDED → ARCHIVED)
- Authorization model (operator tiers, tenant roles, operator→tenant assignments)
- Auth flows (login, refresh, logout, switch-tenant, password reset)
- JWT signing + JWKS endpoint
- Admin REST APIs for operator + tenant-admin use
- Email transactional templates (invite, reset, welcome)
- Audit log of identity events

### What this milestone does NOT own
- Drone, mission, telemetry domain logic (M2+)
- Frontend (M1 backend only — Angular work is a separate parallel track)
- MFA, SSO, OAuth (V2)

---

## 4. Data model

### 4.1 `iam_db` collections

```
users
  _id, email (unique global), passwordHash, firstName, lastName,
  status (ACTIVE | INVITED | SUSPENDED | DELETED),
  userKind (OPERATOR | TENANT_USER),
  operatorRole (OPERATOR_ADMIN | OPERATOR_SUPPORT | null),  // OPERATOR only
  tenantId (null for OPERATOR),                              // TENANT_USER only
  tenantRole (TENANT_OWNER | ADMIN | EDITOR | VIEWER | null),
  tokenVersion (int, default 0),
  lastLoginAt, createdAt, updatedAt

tenants
  _id, name, slug (unique, lowercase regex),
  status (ACTIVE | TRIAL | SUSPENDED | ARCHIVED),
  plan, settings (subdoc), createdBy, createdAt, updatedAt

operator_assignments
  _id, operatorUserId, tenantId, assignedBy, assignedAt
  -- unique compound (operatorUserId, tenantId)

audit_log
  _id, timestamp, actorUserId, action, targetType, targetId,
  tenantId (nullable), metadata, ip, userAgent
  -- TTL index: 365 days
```

**Tokens (Redis):**
- Refresh tokens: key `rt:<userId>:<random>` → `{deviceInfo, createdAt, expiresAt}`, TTL 7d
- Password reset: key `pwr:<tokenHash>` → `{userId, expiresAt}`, TTL 1h
- Token denylist: key `bl:<jti>` → `1`, TTL = remaining token life
- Rate limits: key `rl:login:<email>` → counter, TTL 15m

### 4.2 `tenant_<id>_db` collections (M2+, indexes provisioned in M1)

```
drones, missions, geofences, telemetry (TimeSeries)
```

### 4.3 Indexes

```
users:                  { email: 1 } UNIQUE
                        { userKind: 1 }
                        { tenantId: 1, status: 1 }
                        { status: 1 }
tenants:                { slug: 1 } UNIQUE
                        { status: 1 }
operator_assignments:   { operatorUserId: 1, tenantId: 1 } UNIQUE
                        { tenantId: 1 }
audit_log:              { timestamp: -1 } TTL=31536000s
                        { actorUserId: 1, timestamp: -1 }
                        { tenantId: 1, timestamp: -1 }
```

---

## 5. JWT contract

### 5.1 Access token (RS256, 15-min TTL)

```json
{
  "iss": "https://iam.softbank.com",
  "sub": "<userId>",
  "email": "user@example.com",
  "kind": "OPERATOR" | "TENANT_USER",
  "opRole": "OPERATOR_ADMIN" | "OPERATOR_SUPPORT" | null,
  "tid":   "<activeTenantId>" | null,
  "tRole": "TENANT_OWNER" | "ADMIN" | "EDITOR" | "VIEWER" | null,
  "tv": 0,
  "iat": 1747000000,
  "exp": 1747000900,
  "jti": "<uuid>"
}
```

### 5.2 Refresh token
- Opaque random 256-bit value
- Stored only in Redis
- Rotated on every refresh

### 5.3 JWKS endpoint
- `GET /.well-known/jwks.json` — public, cacheable 1h
- Allows future GCS / external services to verify tokens without IAM round-trip

---

## 6. Tenant context propagation (Phase 1.3 — critical)

`TenantContext` uses Java 25 **Scoped Values**, not `ThreadLocal`:

```java
public final class TenantContext {
    public static final ScopedValue<String> CURRENT = ScopedValue.newInstance();
}
```

`TenantContextFilter` wraps the chain:

```java
ScopedValue.where(TenantContext.CURRENT, tid)
           .run(() -> chain.doFilter(req, res));
```

Benefits over `ThreadLocal`:
- Immutable inside scope
- Auto-cleanup when scope exits — no leak risk
- Propagates cleanly to structured-concurrency child tasks
- Designed for virtual threads from the ground up

**`TenantMongoTemplateRegistry`** caches one `MongoTemplate` per DB, all sharing one `MongoClient` connection pool:
- `forIam()` → `iam_db` template (always-on)
- `forTenant(tenantId)` → `tenant_<id>_db` template (lazy, cached)

Tenant DB provisioning is **explicit**, not implicit on read — typos cannot silently create empty DBs.

---

## 7. API surface (M1)

### Auth (`/api/auth/`)
```
POST   /login                  public
POST   /refresh                public
POST   /logout                 auth
GET    /me                     auth
POST   /forgot-password        public
POST   /reset-password         public
POST   /switch-tenant          auth (operator only)
```

### Operator admin (`/admin/api/`)
```
POST   /tenants                                       OPERATOR_ADMIN
GET    /tenants                                       OPERATOR_*
GET    /tenants/{id}                                  OPERATOR_*
PUT    /tenants/{id}                                  OPERATOR_ADMIN
DELETE /tenants/{id}                                  OPERATOR_ADMIN  (soft)

POST   /operator-users                                OPERATOR_ADMIN
GET    /operator-users                                OPERATOR_*
PUT    /operator-users/{id}                           OPERATOR_ADMIN
DELETE /operator-users/{id}                           OPERATOR_ADMIN  (soft)

POST   /operator-users/{id}/assignments               OPERATOR_ADMIN
DELETE /operator-users/{id}/assignments/{tenantId}    OPERATOR_ADMIN
```

### Tenant admin (`/api/tenant/`)
```
GET    /users                  TENANT_OWNER | ADMIN
POST   /users                  TENANT_OWNER | ADMIN  (invite)
PUT    /users/{id}             TENANT_OWNER | ADMIN
DELETE /users/{id}             TENANT_OWNER | ADMIN  (soft)
GET    /me                     authenticated
```

### Platform / observability
```
GET  /actuator/health
GET  /actuator/info
GET  /actuator/metrics
GET  /actuator/prometheus
GET  /v3/api-docs
GET  /swagger-ui
GET  /.well-known/jwks.json
```

---

## 8. Security policy

| Concern | Mitigation |
|---|---|
| Brute-force login | Redis token bucket: 5 attempts / 15m / email+IP |
| Token revocation | `tokenVersion` in JWT; bump on password change / deactivation; cached check via Redis |
| Refresh-token theft | Rotate on every refresh; immediate invalidation of old |
| Session leak across tenants | Scoped Values + per-tenant DB physical isolation |
| Password storage | BCrypt cost 12 |
| CORS | Allowlist only |
| HTTPS | Enforced via Traefik + HSTS |
| Audit | Every auth + admin event |
| Secrets | Env vars only; no secrets in repo |

---

## 9. Phased delivery (12 phases inside M1)

| Phase | Title | Effort |
|---|---|---|
| 1.1 | Repo & build skeleton | ½ d |
| 1.2 | Local Docker dev environment | ½ d |
| 1.3 | **Multi-tenant Mongo wiring** (critical path) | 3–5 d |
| 1.4 | IAM data model + Mongock indexes | 1 d |
| 1.5 | JWT, JWKS, password hashing | 2–3 d |
| 1.6 | Security filter chain + tenant context propagation | 1–2 d |
| 1.7 | Operator admin APIs | 2 d |
| 1.8 | Tenant-admin APIs | 1 d |
| 1.9 | Email service | 1 d |
| 1.10 | Observability + audit | 1 d |
| 1.11 | Testing foundation (Testcontainers) | 2 d (parallel) |
| 1.12 | CI / Dockerfile / OpenAPI | ½–1 d |

**M1 Definition of Done**: A bash script can boot the stack, create a tenant via API, invite an operator, log them in, switch tenants, and exercise tenant-scoped endpoints — all within 30 seconds, with audit log entries proving each step.

---

## 10. Out of scope for M1

- Drone / mission / telemetry domain
- Frontend (Angular SPA)
- MFA, OAuth, SSO
- Multi-region deployment
- Tenant-level rate limiting (only login-level)
- Hard delete / GDPR right-to-erasure flows
- Backup / restore tooling

---

## 11. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Multi-tenant Mongo wiring bugs leak data between customers | Explicit per-tenant DB (physical isolation); fail-loud on missing context; integration tests asserting cross-tenant blocks |
| Java 25 + Spring Boot version compatibility | Verify Spring Boot 3.5+ in Phase 1.1; downgrade language level only if unavoidable |
| Existing GCS migration drags M1 | M1 ships GCS-free; migration is M2+ behind JWT contract |
| Scoped Values are new — bugs in libraries | Limited blast radius (only TenantContext); fall back to ThreadLocal if needed |

---

## 12. References

- Existing repo docs: `docs/architecture/01-system-overview.md` through `05-api-integration-design.md` (largely superseded by this spec)
- Brainstorming session: 2026-05-11 conversation
