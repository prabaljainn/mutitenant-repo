# Class reference

Every production class in `platform/src/main/java`, organized by package. Each entry: one-line role, key methods/fields, and "see also" pointers when behavior is split across files.

Source counts: **47 production classes** across `common`, `iam`, and the application root.

---

## `com.orochiverse.platform`

### `PlatformApplication`
Spring Boot entry point. `@SpringBootApplication @EnableMongock`. Single `main(args)` delegates to `SpringApplication.run`. Only the entry point lives here — feature code lives in modules.

---

## `common.audit/` — append-only audit log

### `AuditAction` (enum)
Enumerates every action that gets recorded: `LOGIN_SUCCESS`, `LOGIN_FAILED`, `TENANT_CREATED`, `TENANT_DELETED`, `OPERATOR_ASSIGNED`, `OPERATOR_REVOKED`, `USER_INVITED`, `PASSWORD_CHANGED`, `TENANT_SWITCHED`, etc. Adding an action here is the first step in instrumenting a new flow.

### `AuditEntry` (record, `@Document("audit_log")`)
One row per recorded action. Fields: `id`, `action`, `actorUserId`, `tenantId` (nullable for cross-tenant ops), `timestamp`, `details` (free-form `Map<String,Object>`). Static factory `AuditEntry.of(action, actorUserId)` for the common case.

### `AuditEntryRepository` (Spring Data)
Reads and appends to `iam_db.audit_log`. Methods: `findAllByActorUserIdOrderByTimestampDesc(userId, Pageable)`, `findAllByTenantIdOrderByTimestampDesc(tenantId, Pageable)`. Append-only by convention; nothing in the codebase calls `delete*`.

---

## `common.data/` — marker annotations

### `@IamScoped`
Tag annotation for repositories/services that read/write `iam_db`. Documentation only — has no runtime behavior. Lets `RepositoryDisciplineTest` reason about intent.

### `@TenantScoped`
Counterpart to `@IamScoped`. Marks code that operates on per-tenant DBs through `TenantMongoTemplateRegistry.forCurrentTenant()`. Anything in `tenant.*` or `gcs.*` should carry this annotation.

---

## `common.migrations.iam/` — Mongock changesets for `iam_db`

### `IamBaselineIndexes`
`@ChangeUnit(id="iam-baseline-indexes-001", order="001")`. Runs at startup via `@EnableMongock` on `PlatformApplication`. Creates every index on `iam_db.users`, `iam_db.tenants`, `iam_db.operator_assignments`, and `iam_db.audit_log` — including the TTL on `audit_log.timestamp` for 1-year retention.

To add a new collection or index, write a new `@ChangeUnit` class with a higher `order` value. Editing this class will not re-run; Mongock tracks changesets by ID.

---

## `common.tenant/` — tenant context + per-tenant Mongo plumbing

The package that makes "developers write single-tenant code" possible. Read these in order if you're new: `TenantId` → `TenantContext` → `TenantMongoTemplateRegistry` → `TenantDatabaseProvisioner`.

### `TenantId`
Immutable utility for the tenant-ID format (`^[a-z0-9][a-z0-9_-]{0,49}$`). Methods: `requireValid(id)` returns the id if valid (throws `IllegalArgumentException` otherwise); `dbName(id)` returns `tenant_<id>_db`. Used everywhere a tenant ID enters the system.

### `MissingTenantContextException`
Thrown by `TenantContext.requireCurrent()` when no tenant is bound. Means a tenant-scoped piece of code ran outside a tenant scope — almost always a routing/wiring bug.

### `TenantContext`
Carries the active tenant ID for a unit of work using a Java 25 `ScopedValue`. Public surface: `runIn(tid, Runnable)`, `callIn(tid, CallableOp)`, `requireCurrent()`, `current()` (Optional), `isBound()`. **Why ScopedValue not ThreadLocal**: see the class javadoc — auto-cleanup, immutable inside scope, designed for virtual threads.

### `TenantMongoTemplateRegistry`
Caches one `MongoTemplate` per tenant DB; all share one underlying `MongoClient` connection pool. Methods: `forCurrentTenant()` (resolves through `TenantContext`), `forTenant(id)`, `evictTenant(id)`. **This is the only class in `tenant.*` / `gcs.*` should ever use to talk to Mongo** — `RepositoryDisciplineTest` enforces it.

### `TenantDatabaseProvisioner`
Explicit per-tenant DB lifecycle. `provision(tenantId)` writes a `_provisioning_marker` doc (so the DB exists; Mongo creates DBs lazily on first write) and creates per-tenant indexes. `deprovision(tenantId)` drops the DB and evicts the cached template. Called from the operator-admin tenant-CRUD APIs (Phase 1.7).

### `TenantMongoConfig`
`@Configuration` that wires the `MongoClient` and `TenantMongoTemplateRegistry`. Guarded by `@ConditionalOnProperty(prefix="spring.data.mongodb", name="uri")` so it stays inert under the `test` profile (which excludes Mongo autoconfig).

---

## `common.security/` — auth/identity stack (Phases 1.5 + 1.6)

### `SecurityConfig`
The single Spring `SecurityFilterChain` for the application. Disables CSRF, sessions, form login, basic auth, and Spring's logout. Permits `/.well-known/jwks.json`, `/actuator/health/**`, `/actuator/info`, `/actuator/prometheus`, `/actuator/metrics/**`, the Swagger paths, and `/api/auth/{login,refresh,forgot-password,reset-password}`. Every other request is `.authenticated()`. Inserts `JwtAuthenticationFilter` ahead of `UsernamePasswordAuthenticationFilter`; wires `JsonAuthenticationEntryPoint` (401) and `JsonAccessDeniedHandler` (403). `@EnableMethodSecurity` so `@PreAuthorize` works.

### `common.security.principals/`

These three enums describe **who** is authenticating, independent of how. They live in `common` (not `iam`) because the JWT contract — and therefore every module that authorizes a request — has to read them from a token without depending on `iam`.

- `UserKind` — `OPERATOR` (Orochiverse staff, cross-tenant) or `TENANT_USER` (one customer's user, single tenant).
- `OperatorRole` — `OPERATOR_ADMIN` or `OPERATOR_SUPPORT`. Uniform across all assigned tenants.
- `TenantRole` — `TENANT_OWNER`, `ADMIN`, `EDITOR`, `VIEWER`.

### `common.security.keys/` — RSA keypair management

#### `JwtKeyProvider` (interface)
Single source of truth for the active RSA keypair + `kid`. Methods: `signingKey()` (RSAPrivateKey), `verificationKey()` (RSAPublicKey), `activeKeyId()`.

#### `EphemeralRsaKeyProvider`
Generates a fresh RSA-2048 keypair on construction. Used in dev / test / integration. Logs a clearly-tagged WARN at startup so it can never be confused with the prod provider.

#### `FileRsaKeyProvider`
Loads a PEM-encoded keypair from disk: PKCS#8 private key + X.509 SubjectPublicKeyInfo public key. Helpful error message tells you to convert legacy PKCS#1 (`openssl pkcs8 -topk8 …`). Generation: `openssl genpkey -algorithm RSA -out private.pem -pkeyopt rsa_keygen_bits:2048 && openssl rsa -in private.pem -pubout -out public.pem`.

#### `JwtKeysConfig`
Selects which provider to wire: `FileRsaKeyProvider` if `platform.security.jwt.private-key-path` is set (prod), else `EphemeralRsaKeyProvider`. Both beans are `@ConditionalOnMissingBean(JwtKeyProvider.class)` so a test can override via `@TestConfiguration`.

### `common.security.jwt/` — JWT issuance + verification

#### `JwtProperties` (record, `@ConfigurationProperties("platform.security.jwt")`)
Bound from YAML / env: `issuer`, `accessTokenTtl` (Duration), `clockSkew` (Duration, defaults to 30s if unset), `privateKeyPath`, `publicKeyPath`, `keyId`. Validates `issuer` and `accessTokenTtl` in the compact constructor.

#### `JwtConfig`
`@EnableConfigurationProperties(JwtProperties.class)` + a `Clock systemUTC()` bean (so tests can override with `Clock.fixed(...)`).

#### `AccessTokenClaims` (record)
The strongly-typed view of the access-token payload from spec §5.1. Fields: `issuer`, `userId`, `email`, `kind`, `operatorRole`, `activeTenantId`, `tenantRole`, `tokenVersion`, `jti`, `issuedAt`, `expiresAt`. The compact constructor enforces the kind-specific invariants (operator must have `operatorRole`; tenant user must have `activeTenantId` + `tenantRole`).

#### `AccessTokenIssuer`
Builds and signs RS256 JWTs. `issue(...)` takes the claim values, mints `iat`/`exp` from the injected `Clock`, generates a fresh `jti`, and returns `Issued(token, claims)`. The `kid` header is set to `JwtKeyProvider.activeKeyId()`.

#### `AccessTokenVerifier`
Verifies signature, issuer, and lifetime; unpacks claims back to `AccessTokenClaims`. Anything that fails — bad signature, expired, wrong issuer, malformed claims — surfaces as `JwtVerificationException`. Phase 1.5 stops here; the Phase 1.6 filter does the SecurityContext binding.

#### `JwtVerificationException`
Single error type the auth filter catches. Wraps the underlying jjwt exception so callers don't have to import jjwt's exception hierarchy.

### `common.security.jwks/`

#### `JwksController`
`GET /.well-known/jwks.json` returning the JWK Set with the active public key. Includes `kty`, `n`, `e`, `kid`, `alg=RS256`, `use=sig`. `Cache-Control: max-age=3600, public`.

### `common.security.passwords/`

#### `PasswordHashing`
BCrypt cost-12 hash + verify. `hash(raw)` rejects blank input; `matches(raw, stored)` returns false (not throws) on mismatch and tolerates null inputs. The `BCRYPT_COST` constant is the single grep target for "what's our work factor".

#### `PasswordHashingConfig`
Provides the `BCryptPasswordEncoder` bean at the configured cost.

### `common.security.auth/` — JWT auth filter + supporting types (Phase 1.6)

#### `BearerTokenExtractor`
Static `extract(HttpServletRequest)` returning `Optional<String>`. Case-insensitive scheme match per RFC 6750 §2.1; trims whitespace.

#### `AuthorityResolver`
Maps `AccessTokenClaims` → list of Spring `GrantedAuthority`. Emits two roles per principal: a kind authority (`ROLE_OPERATOR` / `ROLE_TENANT_USER`) and a role authority (`ROLE_OPERATOR_ADMIN`, `ROLE_TENANT_OWNER`, etc.). Tenant ID is **not** encoded in authorities — it lives on `TenantContext` for the duration of the request.

#### `AuthenticatedUser`
`AbstractAuthenticationToken` carrying the verified `AccessTokenClaims` as principal. Returns `claims.userId()` from `getName()`. `setAuthenticated(false)` is rejected — the instance only ever exists post-verification, downgrading silently disables authz.

#### `JwtAuthenticationFilter`
`OncePerRequestFilter` that: (a) extracts the bearer, (b) verifies via `AccessTokenVerifier`, (c) builds an `AuthenticatedUser` and sets `SecurityContextHolder`, (d) if the `tid` claim is present, runs the rest of the chain inside `TenantContext.callIn(tid, ...)`. On any verification failure: clears context, lets chain continue — the `.authenticated()` matcher then triggers `JsonAuthenticationEntryPoint`.

What it deliberately does **not** do: (a) `tokenVersion` revocation check (deferred to Phase 1.10's Redis cache), (b) per-request operator-tenant-assignment re-check (the `switch-tenant` flow validates at issue time).

#### `JsonAuthenticationEntryPoint`
401 in JSON: `{status, error: "unauthorized", message, path, timestamp}`. No parser-level detail leaked to clients.

#### `JsonAccessDeniedHandler`
403 counterpart: `{status, error: "forbidden", ...}`.

#### `MeController`
`GET /api/auth/me` — returns the current principal. Used by clients to introspect their token without a server round-trip to the user repo, and by the integration tests as a smoke check on the whole filter chain. Body includes `tenantContextBound` so tests can verify the filter actually wrapped the chain in `TenantContext.callIn`.

---

## `iam/` — operator + tenant-user identity (lives in `iam_db`)

### `iam.tenants/`

#### `Tenant` (record, `@Document("tenants")`)
A customer organization. Fields: `id` (the tenant ID), `name`, `status` (`TenantStatus`), `plan`, `createdBy`, `createdAt`, `updatedAt`, `settings` (free-form Map). Factory `newTrial(id, name, plan, createdBy)`. Validates id via `TenantId.requireValid` and rejects blank names.

#### `TenantStatus` (enum)
`TRIAL`, `ACTIVE`, `SUSPENDED`, `DELETED`. Lifecycle: TRIAL → ACTIVE on conversion, → SUSPENDED on billing/policy issue, → DELETED on operator-initiated tear-down (soft delete; per-tenant DB drop happens via `TenantDatabaseProvisioner.deprovision`).

#### `TenantRepository` (Spring Data, `@IamScoped`)
`MongoRepository<Tenant, String>`. Methods: `findAllByStatus(status)`. Lives in `iam_db.tenants`.

### `iam.users/`

#### `User` (record, `@Document("users")`)
A platform user — operator OR tenant user, same collection, discriminated by `kind`. Fields: `id`, `email`, `passwordHash`, `firstName`, `lastName`, `status`, `kind`, `operatorRole` (nullable), `tenantId` (nullable), `tenantRole` (nullable), `tokenVersion`, `lastLoginAt`, `createdAt`, `updatedAt`. Factories: `newOperator(...)`, `newTenantUser(...)`. Compact constructor enforces kind-specific invariants. `canAccess(tenantId)` for tenant users; throws for operators (operator access goes through `OperatorAssignment`).

#### `UserStatus` (enum)
`INVITED`, `ACTIVE`, `SUSPENDED`, `DELETED`. Auth must reject anything other than `ACTIVE`.

#### `UserRepository` (Spring Data, `@IamScoped`)
`MongoRepository<User, String>`. Methods: `findByEmailIgnoreCase`, `existsByEmailIgnoreCase`, `findAllByKindAndStatus`, `findAllByTenantIdAndStatus`, `countByTenantId`.

### `iam.operators/`

#### `OperatorAssignment` (record, `@Document("operator_assignments")`)
Grants an OPERATOR user the right to act inside one tenant. Fields: `id`, `operatorUserId`, `tenantId`, `assignedBy`, `assignedAt`. Factory `grant(operatorUserId, tenantId, assignedBy)`. The unique compound index `(operatorUserId, tenantId)` (created by `IamBaselineIndexes`) blocks duplicate grants at the DB.

#### `OperatorAssignmentRepository` (Spring Data, `@IamScoped`)
`MongoRepository<OperatorAssignment, String>`. Methods: `findAllByOperatorUserId`, `findAllByTenantId`, `existsByOperatorUserIdAndTenantId`.

---

## `tenant/` (M1)

Currently empty save for `package-info.java`. Tenant-admin self-service controllers land here in Phase 1.8.

## `gcs/` (M2+ placeholder)

Reserved. The `RepositoryDisciplineTest` already enforces that anything appearing here must use `TenantMongoTemplateRegistry` rather than injecting `MongoTemplate` directly.

---

## Cross-cutting rules enforced by ArchUnit

1. `tenant/` and `gcs/` may NOT inject `MongoTemplate` or extend `MongoRepository` — they MUST use `TenantMongoTemplateRegistry.forCurrentTenant()`. (`RepositoryDisciplineTest`)
2. `iam/` and `tenant/` and `gcs/` may NOT depend on `MongoClient` directly — only `common.*` is allowed. (`RepositoryDisciplineTest`)
3. `common/` may NOT depend on `iam`, `tenant`, or `gcs`. (`PackageBoundaryTest`) — this is what forced the `principals` enums into `common.security.principals`.
4. `iam/` and `tenant/` may NOT depend on each other. (`PackageBoundaryTest`)
5. Nothing may depend on `gcs/` until M2. (`PackageBoundaryTest`)
