# Class reference

Every production class in `platform/src/main/java`, organized by package.
Each entry: one-line role, key methods/fields, and "see also" pointers when
behavior is split across files.

Source counts: **~109 production classes** across `common`, `iam`, and the
application root.

---

## `com.orochiverse.platform`

### `PlatformApplication`
Spring Boot entry point. `@SpringBootApplication @EnableMongock`. Single
`main(args)` delegates to `SpringApplication.run`.

---

## `common.audit/` — append-only audit log

### `AuditAction` (enum)
Every action that gets recorded — `LOGIN_SUCCESS`, `LOGIN_FAILURE`,
`PASSWORD_RESET_REQUESTED/COMPLETED`, `PASSWORD_CHANGED`, `TENANT_CREATED/
UPDATED/ARCHIVED`, `TENANT_DB_PROVISIONED/DEPROVISIONED`, `OPERATOR_INVITED/
ROLE_CHANGED/SUSPENDED/DELETED`, `OPERATOR_ASSIGNMENT_GRANTED/REVOKED`,
`TENANT_USER_INVITED/ROLE_CHANGED/SUSPENDED/DELETED`,
`TENANT_SETTING_UPDATED/DELETED/TESTED`, `TENANT_SWITCHED`, `TOKEN_REVOKED`.
Adding an action is step 1 of instrumenting a new flow.

### `AuditEntry` (record, `@Document("audit_log")`)
One row per recorded action. Fields: `id`, `timestamp`, `actorUserId`,
`action`, `targetUserId`, `targetEntityId`, `tenantId`, `metadata`,
`actorIp`, `userAgent`. Static factory `AuditEntry.of(action, actorUserId, metadata)`.

### `AuditEntryRepository` (Spring Data, `@IamScoped`)
Reads + appends `iam_db.audit_log`. Methods include
`findAllByActorUserIdOrderByTimestampDesc(userId, Pageable)`,
`findAllByTenantIdOrderByTimestampDesc(tenantId, Pageable)`. TTL'd at 90
days by index on `timestamp`.

---

## `common.data/` — marker annotations

### `@IamScoped`
Documentation-only tag for repositories/services that touch `iam_db`.

### `@TenantScoped`
Counterpart — marks code that operates on per-tenant DBs through
`TenantMongoTemplateRegistry.forCurrentTenant()`.

---

## `common.email/` — outbound mail (Phase 1.9)

### `EmailProperties` (record, `@ConfigurationProperties("platform.email")`)
`from`, `replyTo`, `baseUrl`. The `baseUrl` is what gets baked into invite
and reset links — distinct from `spring.mail.host` (the SMTP server).

### `EmailService` (interface)
`send(to, subject, templateName, model)` — single entry point. One
implementation today (`SmtpEmailService`), drop-in replaceable.

### `SmtpEmailService`
Synchronous SMTP via Spring Mail. Renders the named Thymeleaf template
(TEXT mode) with the model, sends. Logs to/subject on success; throws on
SMTP failure (callers don't roll back user-creation on email failure).

### `TextTemplateEngine` config
Qualifier-named `emailTemplateEngine` to avoid clashing with the HTML
auto-config. Templates in `src/main/resources/templates/email/*.txt`.

---

## `common.migrations.iam/` — Mongock changesets for `iam_db`

### `IamBaselineIndexes`
`@ChangeUnit(id="iam-baseline-indexes-001", order="001")`. Runs at startup.
Creates every index on `iam_db.users`, `iam_db.tenants`,
`iam_db.operator_assignments`, `iam_db.audit_log`, including the
`audit_log.timestamp` TTL.

To add a new index, write a new `@ChangeUnit` with a higher `order` —
editing this class doesn't re-run; Mongock tracks by ID.

---

## `common.observability/` — request context + metrics (Phase 1.10)

### `RequestIdMdcFilter`
`@Order(HIGHEST_PRECEDENCE+10)` — runs before any auth filter. Reads or
generates an `X-Request-Id` (16-char), populates the `requestId` MDC slot,
echoes it back as a response header, clears MDC in `finally`. Renamed from
`RequestContextFilter` to avoid a Spring bean-name collision.

### `AuthMetrics`
Micrometer counters, pre-registered with every tag combination so
Prometheus scrapes see zero before the first request:
- `platform_login_attempts_total{outcome=success|failure|rate_limited}`
- `platform_invite_emails_total{kind=operator|tenant_user}`
- `platform_password_resets_total{stage=requested|completed}`
- `platform_token_version_check_failures_total`

---

## `common.openapi/`

### `OpenApiConfig`
Wires the bearer-auth security scheme into the generated OpenAPI doc so
Swagger UI shows the "Authorize" button. Global `SecurityRequirement` since
most of the surface is authenticated.

---

## `common.tenant/` — tenant context + per-tenant Mongo plumbing

Read in order: `TenantId` → `TenantContext` → `TenantMongoTemplateRegistry`
→ `TenantDatabaseProvisioner`.

### `TenantId`
Immutable utility for the tenant-ID format (`^[a-z0-9][a-z0-9_-]{0,49}$`).
`requireValid(id)` and `dbName(id)` (returns `tenant_<id>_db`).

### `MissingTenantContextException`
Thrown by `TenantContext.requireCurrent()` when no tenant is bound.

### `TenantContext`
Java 25 `ScopedValue` carrying the active tenant ID for a unit of work.
Public surface: `runIn(tid, Runnable)`, `callIn(tid, CallableOp)`,
`requireCurrent()`, `current()` (Optional), `isBound()`. Auto-cleanup +
virtual-thread-friendly is the point.

### `TenantMongoTemplateRegistry`
Caches one `MongoTemplate` per tenant DB; all share one `MongoClient`
connection pool. `forCurrentTenant()` resolves through `TenantContext`.
**Only class in `tenant.*`/`gcs.*` permitted to talk to Mongo** —
enforced by `RepositoryDisciplineTest`.

### `TenantDatabaseProvisioner`
Explicit per-tenant DB lifecycle. `provision(tenantId)` writes a marker
doc + creates per-tenant indexes; `deprovision(tenantId)` drops the DB +
evicts the cache. Called from `TenantsAdminService.create/softDelete`.

### `TenantMongoConfig`
`@Configuration` wiring the `MongoClient` and `TenantMongoTemplateRegistry`.
`@ConditionalOnProperty(prefix="spring.data.mongodb", name="uri")` so it
stays inert under the `test` profile.

---

## `common.security/` — auth / identity stack

### `SecurityConfig`
The single `SecurityFilterChain`. Disables CSRF, sessions, form login,
basic auth. Permits `/.well-known/jwks.json`, `/actuator/health/**`,
`/actuator/info`, Swagger paths, and credential-bearing auth endpoints
(`/api/auth/{login,refresh,forgot-password,reset-password,accept-invite}`).
`/actuator/prometheus` + `/actuator/metrics/**` fall through to
`.authenticated()` — **not** public (changed during the pre-deploy
hardening pass). Everything else `.authenticated()`. Inserts
`JwtAuthenticationFilter` ahead of `UsernamePasswordAuthenticationFilter`;
wires `JsonAuthenticationEntryPoint` (401) + `JsonAccessDeniedHandler`
(403). `@EnableMethodSecurity` so `@PreAuthorize` works.

### `common.security.principals/`

Three enums describe **who** is authenticating, independent of how. They
live in `common` (not `iam`) because the JWT contract has to read them
from a token without depending on `iam`.

- `UserKind` — `OPERATOR` or `TENANT_USER`.
- `OperatorRole` — `OPERATOR_ADMIN` or `OPERATOR_SUPPORT`.
- `TenantRole` — `TENANT_OWNER`, `ADMIN`, `EDITOR`, `VIEWER`.

### `common.security.keys/` — RSA keypair management

- `JwtKeyProvider` (interface) — `signingKey()`, `verificationKey()`, `activeKeyId()`.
- `EphemeralRsaKeyProvider` — generates fresh RSA-2048 on construction. Dev/test only; tagged WARN at startup.
- `FileRsaKeyProvider` — loads PEM PKCS#8 + X.509 from disk. Clear error if you feed it PKCS#1.
- `JwtKeysConfig` — picks one or the other based on `platform.security.jwt.private-key-path`.

### `common.security.jwt/`

- `JwtProperties` — bound from YAML/env: issuer, accessTokenTtl, clockSkew, keys.
- `JwtConfig` — `Clock systemUTC()` bean (overrideable in tests).
- `AccessTokenClaims` (record) — strongly-typed view of token payload; compact constructor enforces kind-specific invariants.
- `AccessTokenIssuer` — builds + signs RS256 JWTs.
- `AccessTokenVerifier` — verifies signature, issuer, lifetime; unpacks claims. All failures → `JwtVerificationException`.
- `JwtVerificationException` — single error type the filter catches.

### `common.security.jwks/`

- `JwksController` — `GET /.well-known/jwks.json`. `Cache-Control: max-age=3600, public`.

### `common.security.passwords/`

- `PasswordHashing` — BCrypt cost 12. `hash(raw)` / `matches(raw, stored)`. Rejects blank.
- `PasswordHashingConfig` — provides the `BCryptPasswordEncoder` bean.

### `common.security.auth/` — JWT auth filter + supporting types

#### `BearerTokenExtractor`
Static `extract(HttpServletRequest)` returning `Optional<String>`. Case-insensitive scheme per RFC 6750.

#### `AuthorityResolver`
Maps `AccessTokenClaims` → list of Spring `GrantedAuthority`. Emits a kind
authority (`ROLE_OPERATOR` / `ROLE_TENANT_USER`) and a role authority
(`ROLE_OPERATOR_ADMIN`, `ROLE_TENANT_OWNER`, …). Tenant ID is **not**
encoded in authorities — it lives on `TenantContext`.

#### `AuthenticatedUser`
`AbstractAuthenticationToken` carrying the verified claims as principal.
`getName()` returns `claims.userId()`. `setAuthenticated(false)` is rejected.

#### `JwtAuthenticationFilter`
`OncePerRequestFilter` that: (a) extracts the bearer; (b) verifies via
`AccessTokenVerifier`; (c) consults `TokenVersionLookup` and rejects
tokens whose `tv` claim is stale; (d) builds an `AuthenticatedUser` and
sets `SecurityContextHolder`; (e) populates `userId` + `tenantId` MDC
slots; (f) if the `tid` claim is present, runs the rest of the chain
inside `TenantContext.callIn(tid, …)`. MDC cleared in `finally`.

#### `TokenVersionLookup` (interface)
SPI for the `tokenVersion` check. Lives in `common.security.auth` so the
filter depends only on this contract. Implemented by
`iam.auth.TokenVersionResolver`. `currentVersion(userId)` returns `-1`
when the user is gone. `invalidate(userId)` evicts the cache after
password reset / suspend / delete / role change.

#### `JsonAuthenticationEntryPoint`
401 in JSON: `{status, error: "unauthorized", message, path, timestamp}`.

#### `JsonAccessDeniedHandler`
403 counterpart.

#### `MeController`
`GET /api/auth/me` — returns the current principal. Includes
`tenantContextBound` so ITs can verify the filter actually wrapped the
chain in `TenantContext.callIn`.

---

## `iam/` — operator + tenant-user identity (`iam_db`)

### `iam.tenants/`

- `Tenant` (record, `@Document("tenants")`) — `id`, `name`, `status`, `plan`, `settings`, `createdBy`, `createdAt`, `updatedAt`. Factory `newTrial(...)`.
- `TenantStatus` (enum) — `TRIAL`, `ACTIVE`, `SUSPENDED`, `ARCHIVED`.
- `TenantRepository` (Spring Data, `@IamScoped`) — `findAllByStatus`, `countByStatus`, plus `searchByName` / `searchByStatusAndName` (case-insensitive regex for the `?q=` admin filter).

### `iam.users/`

- `User` (record, `@Document("users")`) — operator OR tenant user, discriminated by `kind`. Factory methods, kind-specific invariants, `canAccess(tenantId)`.
- `UserStatus` (enum) — `INVITED`, `ACTIVE`, `SUSPENDED`, `DELETED`. Auth only accepts `ACTIVE`.
- `UserRepository` (Spring Data, `@IamScoped`) — `findByEmailIgnoreCase`, `existsByEmailIgnoreCase`, `findAllByKindAndStatus`, `findAllByTenantIdAndStatus`, `countByTenantId`, `countByKindAndStatus`, `countByStatus`.

### `iam.operators/`

- `OperatorAssignment` (record, `@Document("operator_assignments")`) — grants an OPERATOR the right to act inside one tenant.
- `OperatorAssignmentRepository` — unique compound index `(operatorUserId, tenantId)`.

### `iam.tokens/` — single-use tokens for invite + reset

- `TokenPurpose` (enum) — `INVITE_ACCEPT`, `PASSWORD_RESET`.
- `SingleUseToken` (record) — `token`, `userId`, `purpose`, `issuedAt`, `expiresAt`.
- `SingleUseTokenStore` (interface) — `issue(userId, purpose)`, `consume(token, expectedPurpose)`, `revokeAllForUser(userId)`. Throws `InvalidTokenException` on miss/expiry.
- `InMemorySingleUseTokenStore` — Caffeine-backed, 7-day TTL on INVITE_ACCEPT, 1-hour TTL on PASSWORD_RESET. Replaceable with a Redis-backed implementation later.
- `InvalidTokenException` — caught by `AuthExceptionHandler`, surfaces as 401.

### `iam.auth/` — `/api/auth/*` flows

- `AuthService` — login / refresh / logout / switch-tenant / forgot-password / reset-password / accept-invite. Owns audit + metrics for these flows; consults `LoginRateLimiter` and `TokenVersionLookup`.
- `AuthController` — REST surface on `/api/auth/*`. Tagged `Auth` in Swagger.
- `AuthDtos` — every request/response shape.
- `AuthExceptionHandler` — `@RestControllerAdvice` mapping
  `InvalidCredentialsException` → 401, `InvalidRefreshTokenException` → 401,
  `OperatorNotAssignedException` → 403, `InvalidTokenException` → 401,
  `RateLimitExceededException` → 429.
- `RefreshTokenStore` (interface) + `InMemoryRefreshTokenStore` — opaque refresh tokens, rotation on every use.
- `RefreshToken` (record) — token, userId, issuedAt, expiresAt.
- `LoginRateLimiter` — Caffeine-backed sliding window. 5 attempts per `(email_lowercase, ip)` per 15 minutes. `recordSuccess` clears the bucket.
- `RateLimitExceededException` — surfaces as 429.
- `TokenVersionResolver` — `iam`-side implementation of `TokenVersionLookup`. Caffeine cache, 30s TTL, 10k entries. Cache miss → `users.findById(...).tokenVersion()` or `-1` if gone.
- `BootstrapOperatorRunner` — creates the first OPERATOR_ADMIN from `PLATFORM_BOOTSTRAP_OPERATOR_EMAIL/PASSWORD` if no operator exists. Idempotent; safe to leave configured.

### `iam.admin/` — `/admin/api/*` surface

#### `iam.admin.common/`
- `AdminExceptions` — typed exceptions (`NotFoundException`, `ConflictException`, `UnprocessableException`).
- `AdminExceptionHandler` — `@RestControllerAdvice` scoped to `iam.admin`, `iam.tenantadmin`, `iam.settings`. Maps to JSON 404/409/422/400.

#### `iam.admin.tenants/`
- `TenantsAdminController` — `/admin/api/tenants`. GET (with `?status=` and `?q=` filters), POST/PUT/DELETE.
- `TenantsAdminService` — create writes the tenant + provisions the per-tenant DB; soft-delete archives + drops the DB + clears tenant settings (in try/catch so audit still lands on cleanup failure).
- `TenantDtos`.

#### `iam.admin.operators/`
- `OperatorsAdminController` + `OperatorsAdminService` — operator CRUD (`/admin/api/operators`). Invite, list, role-change, suspend, soft-delete. **Bumps `tokenVersion` on role change / suspend / delete** and invalidates the resolver cache.
- `OperatorAssignmentsAdminController` + `OperatorAssignmentsAdminService` — grant/revoke an operator's tenant access.
- `OperatorDtos`, `AssignmentDtos`.

#### `iam.admin.audit/`
- `AuditAdminController` — `GET /admin/api/audit?page=&size=&actorUserId=&tenantId=`. Read-only.

#### `iam.admin.stats/`
- `StatsAdminController` — `GET /admin/api/stats/overview` returning `{tenants, tenantUsers, pendingInvites}` in one round-trip.
- `StatsAdminService` — three `countByX` queries.
- `StatsDtos`.

#### `iam.admin.tenantusers/`
- `AdminTenantUsersController` — `/admin/api/tenants/{tenantId}/users/*`. Wraps every handler in `TenantContext.callIn(pathTenantId, …)` so operators can manage tenant users without `switch-tenant`. Reuses `iam.tenantadmin.TenantUsersService` unchanged.

### `iam.tenantadmin/` — `/api/tenant/*` self-service surface

- `TenantMeController` — `GET /api/tenant/me` returning combined user + tenant view. TENANT_USER-only.
- `TenantUsersController` + `TenantUsersService` — invite / list / get / update / delete tenant users. **Bumps `tokenVersion` on role change / suspend / delete.** TENANT_OWNER + ADMIN write, every TENANT_USER reads. Owner-protection: cannot demote/suspend/delete the last active TENANT_OWNER.
- `TenantSettingsController` — `GET /api/tenant/settings` + `GET /api/tenant/settings/{kind}`. **Read-only.** OWNER + ADMIN only (EDITOR/VIEWER 403). Tenant id comes from `TenantContext` — no path param, structurally cannot ask about another tenant.
- `TenantSelfDtos`.

### `iam.settings/` — extensible per-tenant settings store

One collection (`tenant_settings`), keyed by `(tenantId, kind)`. Adding a
new kind = one enum value + one `SettingsKindHandler` bean.

- `SettingsKind` (enum) — `MQTT`, `DJI`.
- `TenantSetting` (record, `@Document("tenant_settings")`) — composite `_id = "<tenantId>:<kind>"`, `values` (free-form map), test-result fields.
- `TenantSettingsRepository` (`@IamScoped`).
- `SettingsKindHandler` (interface) — `kind()`, `secretKeys()`, `validate(values)`, `test(values)`. Service uses this to dispatch.
- `MqttSettingsHandler` — host/port/transport/topicPrefix/username, secret: `password`. `test` opens a TCP socket via `ConnectionTester.tcpProbe`.
- `DjiSettingsHandler` — region/endpointUrl/appKey, secret: `appSecret`. `test` does HTTPS GET via `ConnectionTester.httpProbe`.
- `ConnectionTester` — TCP / HTTPS probes with 3s timeout. **SSRF guard** refuses loopback / link-local / RFC1918 / CGNAT / IPv6 ULA / multicast / wildcard. Dev profile can disable via `platform.settings.allow-private-test-targets=true`.
- `TenantSettingsService` — read/upsert/delete/test. Owns persistence + audit + secret masking on read + secret merging on write (a PUT omitting a secret keeps the stored value). Constructor verifies every `SettingsKind` has a handler bean.
- `TenantSettingsAdminController` — operator-facing CRUD + test endpoint. OPERATOR reads, OPERATOR_ADMIN writes.
- `TenantSettingsDtos`.

---

## `gcs/` (M2+ placeholder)

Reserved. `RepositoryDisciplineTest` enforces that anything here must use
`TenantMongoTemplateRegistry` rather than injecting `MongoTemplate` directly.

---

## Cross-cutting rules enforced by ArchUnit

1. `tenant/` and `gcs/` may NOT inject `MongoTemplate` or extend `MongoRepository` — they MUST use `TenantMongoTemplateRegistry.forCurrentTenant()`. (`RepositoryDisciplineTest`)
2. `iam/` and `tenant/` and `gcs/` may NOT depend on `MongoClient` directly — only `common.*` is allowed. (`RepositoryDisciplineTest`)
3. `common/` may NOT depend on `iam`, `tenant`, or `gcs`. (`PackageBoundaryTest`) — this is what forced `principals` and `TokenVersionLookup` into `common.security.*`.
4. `iam/` and `tenant/` and `gcs/` may NOT depend on each other. (`PackageBoundaryTest`)
5. Nothing may depend on `gcs/` until M2. (`PackageBoundaryTest`)
