# Test reference

Every test class in `platform/src/test/java`, what it verifies, and how to run it.

**Total: 312 tests** — 199 unit (Surefire) + 113 integration (Failsafe). All green.

---

## How to run

| Command | Runs |
|---|---|
| `./mvnw test` | All Surefire (`*Test.java`) — no infra needed. |
| `./mvnw verify` | Surefire + Failsafe (`*IT.java`). Failsafe needs the Docker dev stack up (`./scripts/dev-up.sh` brings up Mongo + Redis + MailHog). |
| `./mvnw test -DskipITs` | Skip integration tests. |
| `./mvnw test -Dtest=ClassName` | Run one Surefire class. |
| `./mvnw verify -Dit.test=ClassName` | Run one Failsafe class. |

**Mongo-backed ITs skip themselves** if the dev Mongo isn't reachable — they're gated on `@EnabledIf("com.orochiverse.platform.testsupport.MongoTestSupport#mongoIsReachable")`. `EmailFlowsIT` is gated on MailHog reachability the same way. The other ITs (`JwksEndpointIT`, `AuthFlowIT`) run under the `test` profile and don't need Mongo.

### CI

`.github/workflows/build.yml` runs `./mvnw verify` on every push to `main` and every PR against a Mongo service container, so both Surefire (199 tests) and Failsafe (113 tests) run in full in CI. `.github/workflows/release.yml` builds a `linux/amd64` Docker image on every push to `main` and pushes it to GHCR as `ghcr.io/<owner>/<repo>/platform:latest`; tagging `v1.2.3` mints a semver release.

---

## Test fixtures (`testsupport/`)

Phase 1.11 collected the IT boilerplate into one package:

- **`MongoTestSupport`** — `mongoIsReachable()` (the `@EnabledIf` probe), `CONNECTION_URI`, and `mongoProps(DynamicPropertyRegistry)`. Every Mongo-backed IT uses these instead of inlining its own copy.
- **`IT`** — static `url(port, path)` / `bearer(token)` / `exchange(port, path, method, token, body, type)` helpers. Replaces the per-IT `private String url(…)` and ad-hoc `TestRestTemplate` calls.
- **`IamFixtures`** — fluent builders: `IamFixtures.operator(suffix).role(OPERATOR_ADMIN).save(users, passwords)` and `IamFixtures.tenantUser(suffix, tenantId).role(TENANT_OWNER).save(users, passwords)`. Returns the persisted `User` so the test can pass it straight into `JwtTestSupport.token`.
- **`JwtTestSupport`** — `JwtTestSupport.token(issuer, user)` mints a signed access token whose claims match the user, bypassing the `/login` HTTP round-trip. Drops per-test setup time on multi-user ITs (e.g. `TenantSelfApiIT` seeds owner+admin+editor+cross-tenant-owner) by skipping four BCrypt verifications. `JwtTestSupport.tokenWithTokenVersion(issuer, user, tv)` mints a token with a stale `tv` for revocation tests.

When to use the bypass: any IT that exercises an authenticated endpoint and isn't itself testing `/login`. Tests of `/login`, the rate limiter, and the password-reset/accept-invite flows still hit `/login` for real, because the credential round-trip is the thing under test.

---

## Unit tests (Surefire)

### `smoke/`

#### `ApplicationBootSmokeTest` — 2 tests
- `context_loads` — `@SpringBootTest` succeeds; proves pom.xml + application.yml are coherent.
- `actuator_health_returns_up` — GET `/actuator/health` returns 200 + `"UP"`.

### `architecture/` — ArchUnit boundary discipline

#### `PackageBoundaryTest` — 5 tests
Enforces the modular-monolith dependency rules:
- iam ↛ tenant
- tenant ↛ iam
- iam ↛ gcs
- tenant ↛ gcs
- common ↛ {iam, tenant, gcs}

A failure here means a class crossed a boundary — fix the class, don't relax the test.

#### `RepositoryDisciplineTest` — 6 tests
Repository-isolation rules (the load-bearing one for multi-tenancy):
- tenant module must not inject `MongoTemplate`
- gcs module must not inject `MongoTemplate`
- tenant module must not extend `MongoRepository`
- gcs module must not extend `MongoRepository`
- only `common.*` may depend on `MongoClient`
- `MongoTemplate` fields may only live in `common.*` or `iam.*`

### `common/tenant/`

#### `TenantIdTest` — 21 tests
Exhaustive coverage of the `^[a-z0-9][a-z0-9_-]{0,49}$` regex: valid IDs of varying length and character classes; invalid IDs (uppercase, spaces, leading hyphen, too long, empty, null). Plus `dbName(id)` returns `tenant_<id>_db`.

#### `TenantContextTest` — 7 tests
Scoped-value plumbing:
- `requireCurrent` outside a scope throws `MissingTenantContextException`
- `current()` returns Optional.empty outside a scope
- `runIn`/`callIn` bind the value for their dynamic extent
- value is unbound after the scope returns
- nested `runIn` re-binds (and reverts) correctly
- `runIn` with an invalid tenant ID rejects via `TenantId.requireValid`
- `callIn` propagates the work's checked exception unchanged

### `iam/users/`

#### `UserTest` — 10 tests
Record invariants for `User`:
- operator factory creates a valid operator
- tenant-user factory creates a valid tenant user
- operator without role rejected
- operator with tenant fields rejected
- tenant user without tenant id rejected
- tenant user with operator role rejected
- tenant user with invalid tenant id rejected
- blank email rejected
- `canAccess(tid)` for tenant users compares tenant id
- `canAccess` for operators throws (operator access goes through `OperatorAssignment`)

### `iam/tenants/`

#### `TenantTest` — 3 tests
- `newTrial` creates a TRIAL tenant with timestamps + empty settings
- invalid id rejected via `TenantId.requireValid`
- blank name rejected

### `common/security/keys/` — Phase 1.5

#### `EphemeralRsaKeyProviderTest` — 3 tests
- generates an RSA-2048 keypair
- key id is unique per JVM and starts with `ephemeral-`
- public + private form a matching pair (encrypt with public, decrypt with private)

#### `FileRsaKeyProviderTest` — 5 tests
- loads a PEM keypair (PKCS#8 + X.509) from disk
- rejects blank `keyId`
- rejects PEM with the wrong label (`CERTIFICATE` instead of `PRIVATE KEY`)
- legacy PKCS#1 (`RSA PRIVATE KEY` label) gets a clear error pointing at `openssl pkcs8`
- missing file fails fast with `IllegalStateException`

### `common/security/jwt/` — Phase 1.5

#### `AccessTokenIssuerVerifierTest` — 9 tests
End-to-end issue + verify with the ephemeral provider:
- operator token round-trips
- tenant-user token round-trips
- operator in a switched tenant carries `tid` but no `tRole`
- token signed by a different key is rejected
- expired token is rejected (1ms TTL + sleep)
- token with wrong `iss` is rejected
- garbage / blank / null token rejected
- operator kind without `opRole` rejected at issue time
- tenant user without `tid`/`tRole` rejected at issue time

### `common/security/passwords/` — Phase 1.5

#### `PasswordHashingTest` — 6 tests
- hash then match succeeds
- wrong password does not match
- two hashes of the same password differ (BCrypt salt is random)
- stored hash starts with `$2a$12$` (cost 12 enforced)
- blank/null password rejected at hash time
- match returns false for blank/null inputs

### `common/security/auth/` — Phase 1.6

#### `AuthorityResolverTest` — 4 tests
- `OPERATOR + OPERATOR_ADMIN` → `[ROLE_OPERATOR, ROLE_OPERATOR_ADMIN]`
- operator in a switched tenant doesn't get a tenant-role authority
- `TENANT_USER + TENANT_OWNER` → `[ROLE_TENANT_USER, ROLE_TENANT_OWNER]`
- every `TenantRole` value yields a distinct `ROLE_*` authority

#### `BearerTokenExtractorTest` — 7 tests
- well-formed `Bearer <token>` extracted
- scheme match is case-insensitive
- whitespace trimmed
- empty when header missing
- empty when scheme is not Bearer (e.g. `Basic …`)
- empty when token part blank
- empty when only the scheme is present

#### `JwtAuthenticationFilterTest` — 5 tests
Mocked-verifier filter behavior:
- no bearer header → chain runs unauthenticated, verifier never called
- invalid token → chain still runs, SecurityContext stays empty
- operator token without `tid` → chain runs, `TenantContext.isBound()` false during chain
- tenant-user token → chain runs inside `TenantContext`, value matches `tid`, scope cleared after
- operator with switched `tid` → tenant context bound

The filter now also calls `TokenVersionLookup.versionOf(userId)` and rejects when the claim's `tv` is stale — covered end-to-end in `AuthApiIT` (revocation-after-suspend).

### `common/observability/` — Phase 1.10

#### `RequestIdMdcFilterTest` — 3 tests
- generates a new `requestId` when the header is absent and populates the MDC for the request lifecycle
- propagates the inbound `X-Request-Id` header verbatim (clients can correlate)
- clears the MDC after the chain returns, even on exception

#### `AuthMetricsTest` — 2 tests
- `loginSuccess()` / `loginFailure()` increment the right Micrometer counters with the right tags
- `tokenRefresh()` increments the refresh counter

### `common/email/` — Phase 1.9

#### `EmailRenderingTest` — 4 tests
- invite template renders with substituted `{name}`, `{tenantName}`, `{inviteUrl}`
- reset-password template renders with substituted `{resetUrl}`, `{ttl}`
- Thymeleaf TEXT mode is used (no HTML escaping artifacts in plain-text emails)
- missing template variable surfaces a clear error rather than rendering `${var}` literally

### `iam/auth/` — Phase 1.7 / 1.9 / 1.10

#### `AuthServiceTest` — 20 tests
The login/refresh/logout/forgot-password/reset-password/accept-invite/switch-tenant happy paths and edge cases — all with mocked repos, mocked `PasswordHashing`, mocked `RefreshTokenStore`, mocked `LoginRateLimiter`, mocked `AccessTokenIssuer`, mocked `EmailService`. Asserts `tv` is bumped on password reset.

#### `InMemoryRefreshTokenStoreTest` — 7 tests
- `issue()` returns a token; `consume()` returns the bound user; second `consume()` is empty (single-use)
- consume after TTL returns empty
- `revoke(userId)` invalidates every outstanding token for that user
- expired entries are evicted opportunistically
- TTL is configurable via `platform.security.refresh-token-ttl`
- bulk revoke is O(n) but bounded
- thread-safe under concurrent `issue + consume`

#### `LoginRateLimiterTest` — 6 tests
- 5th failure within window still allowed; 6th blocked
- successful login resets the bucket for that `(email, ip)`
- different IPs are independent
- different emails are independent
- bucket expires after 15 min and starts fresh
- `tryAcquire` is thread-safe under contention

### `iam/tenantadmin/` — Phase 1.8

#### `TenantUsersServiceTest` — 19 tests
Invite / list / get / update / soft-delete / role-change flows for tenant users, with mocked repos. Verifies:
- invite hashes the password / consumes a single-use invite token
- list excludes soft-deleted users
- update bumps `tv` on role change so old tokens stop working immediately
- update bumps `tv` on suspend (`SUSPENDED` status)
- soft-delete bumps `tv` and writes an `AuditEntry`
- cannot transfer ownership via `update` (that's an explicit endpoint)
- cross-tenant lookup rejected (path tenant ≠ entity tenant)
- email change re-checks uniqueness

### `iam/settings/` — Settings module

#### `MqttSettingsHandlerTest` — 8 tests
- valid MQTT config accepted (`tcp://broker:1883` or `ssl://broker:8883`)
- rejects missing host
- rejects port out of `[1, 65535]`
- rejects invalid scheme (must be `tcp`/`ssl`/`mqtt`/`mqtts`)
- redacts password in the JSON returned to callers
- merge with existing settings preserves password when the new payload omits it (PUT-as-PATCH ergonomics)
- producing `TestPayload` returns the right (host, port, username, password) tuple
- diff against persisted settings is `null`-safe

#### `DjiSettingsHandlerTest` — 6 tests
- valid DJI Cloud API config accepted (appKey, appSecret, license, region)
- rejects missing appKey or appSecret
- rejects unknown region
- redacts `appSecret` and `license` in the response
- merge preserves the secret on partial PUT
- diff produces a stable canonical form

#### `ConnectionTesterTest` — 12 tests
The SSRF-guarded reachability probe used by `/settings/{kind}/test`:
- valid public hostname is accepted and probed (mocked socket)
- success path returns `TestResult.ok()` with the resolved address + latency
- DNS failure returns `TestResult.fail(...)` (no exception escapes)
- TCP connection failure returns `TestResult.fail(...)`
- loopback (`127.0.0.1`, `::1`) rejected
- link-local (`169.254.x.x` — covers AWS instance metadata `169.254.169.254`) rejected
- RFC1918 (`10.x`, `172.16-31.x`, `192.168.x`) rejected
- CGNAT (`100.64.0.0/10`) rejected
- IPv6 ULA (`fc00::/7`) rejected
- multicast / wildcard / unspecified rejected
- the dev flag `platform.settings.allow-private-test-targets=true` lifts the guard (and ONLY in dev)
- guard runs after DNS resolution so a public hostname that resolves to a private IP is still rejected

#### `TenantSettingsServiceTest` — 11 tests
- list returns every kind for the tenant, ordered
- read a single kind returns the redacted JSON
- write upserts via `TenantSettingsRepository` and writes an `AuditEntry`
- write delegates validation + redaction to the registered `SettingsKindHandler` for that kind
- delete removes the document and writes an `AuditEntry`
- soft-cleanup uses `try/finally` so the audit always lands even if cleanup blips
- unknown `kind` returns `404 NotFound`
- mismatched tenant rejected (path tenant ≠ stored tenant)
- test endpoint uses `ConnectionTester` with the kind's `TestPayload`
- merge preserves redacted secret when the inbound JSON omits it
- service is `@Transactional`-free — Mongo writes are durable on return

### `iam/tokens/` — Phase 1.9

#### `InMemorySingleUseTokenStoreTest` — 8 tests
The store backing password-reset and invite tokens:
- `issue(purpose, userId, ttl)` returns a one-shot token
- `consume(token)` returns the bound `(purpose, userId)` and burns the token
- second `consume` returns empty
- consume after TTL returns empty
- purpose mismatch (consuming a reset token as an invite) returns empty
- TTL is per-token
- can issue multiple in-flight tokens per user
- bulk eviction runs lazily on access

---

## Integration tests (Failsafe)

### `integration/`

#### `MongoConnectivityIT` — 3 tests *(needs dev Mongo)*
Sanity that the local stack works:
- driver connects + pings
- replica set status is PRIMARY
- can list databases (proves auth/wire-up)

#### `MultiTenantMongoIT` — 8 tests *(needs dev Mongo)*
Dynamic per-tenant DB lifecycle. Each test allocates a random tenant id (`p13<8-hex>`) so re-runs and CI parallelism don't collide:
- `provision` creates the tenant DB with the marker doc
- `provision` is idempotent (second call is a no-op)
- `forTenant` returns a `MongoTemplate` whose DB equals `tenant_<id>_db`
- writes go to the right DB and don't leak into `iam_db`
- writes for two different tenants land in two different DBs (cross-tenant isolation)
- `deprovision` drops the DB
- `deprovision` evicts the cached template (a fresh one is built on next use)
- end-to-end: provision → write → switch tenant via `TenantContext` → read returns the right doc

#### `IamRepositoriesIT` — 9 tests *(needs dev Mongo)*
Spring Data IAM repositories against real Mongo:
- Mongock baseline indexes were created on `users`, `tenants`, `operator_assignments`, `audit_log`
- `mongockChangeLog` recorded `iam-baseline-indexes-001`
- can save and find a tenant
- can save an operator user with no `tenantId`
- can save a tenant user and find by email case-insensitively
- `users` email uniqueness is enforced by the index (DuplicateKeyException)
- can grant and query operator assignments by operator and by tenant
- operator-assignment uniqueness is enforced (compound index)
- can write and query audit entries

### `common/security/jwks/`

#### `JwksEndpointIT` — 1 test *(test profile, no Mongo)*
- GET `/.well-known/jwks.json` → 200 + `Cache-Control: max-age=3600`. Body has `keys[0]` with `kty=RSA`, `alg=RS256`, `use=sig`, matching `kid`. Reconstructs the RSA public key from `n`/`e` and asserts modulus + exponent match the in-process key provider.

### `common/security/auth/`

#### `AuthFlowIT` — 11 tests *(test profile, no Mongo)*
End-to-end through the real `SecurityFilterChain`. Tokens issued by `AccessTokenIssuer` are sent as Bearer headers to test endpoints registered via `@TestConfiguration`:

`/api/auth/me` (the production controller):
- 401 without a Bearer
- 401 with a garbage Bearer
- 200 with a valid operator token; body has `userId`, `kind=OPERATOR`, `operatorRole=OPERATOR_ADMIN`, `activeTenantId=null`, `tenantContextBound=false`
- 200 with a valid tenant-user token; body has `kind=TENANT_USER`, `activeTenantId=acme`, `tenantRole=TENANT_OWNER`, `tenantContextBound=true`

`/test/whoami` (test controller reading `TenantContext.current()`):
- tenant user → returns `{tenantId: "acme"}`
- pure operator (no `tid`) → returns `{tenantId: null}`

`/test/admin-only` (`@PreAuthorize("hasRole('OPERATOR_ADMIN')")`):
- 200 for `OPERATOR_ADMIN`
- 403 (JSON body) for `OPERATOR_SUPPORT`
- 403 for tenant user

Public endpoints stay open after Phase 1.6 tightening:
- `/.well-known/jwks.json` returns 200 without auth
- `/actuator/health` returns 200 without auth
- `/actuator/prometheus` and `/actuator/metrics/**` now require auth (post-1.10 lockdown)

### `iam/auth/`

#### `AuthApiIT` — 11 tests *(needs dev Mongo)*
End-to-end auth flow over real HTTP + real Mongo:
- `/login` with right creds → 200 + access/refresh pair + matching `kind`/`opRole`
- `/login` wrong password → 401, no token
- `/login` unknown email → 401 (same shape as wrong password — no enumeration)
- `/login` rate-limited after 5 failures within 15min for `(email, ip)`
- `/refresh` rotates the refresh token (single-use)
- `/refresh` with consumed token → 401
- `/logout` revokes the refresh family for the user
- `/me` returns the current principal
- `/switch-tenant` changes the access token's `tid` for an operator
- access token issued before suspend is rejected after suspend (tv-bump end-to-end)
- access token issued before role-change is rejected after role-change (tv-bump end-to-end)

#### `EmailFlowsIT` — 5 tests *(needs dev Mongo + MailHog)*
- `/forgot-password` → MailHog receives an email with a reset URL embedding a single-use token
- `/reset-password` with that token sets a new password, bumps `tv`, and consumes the token
- second consume of the same token → 400
- invite created via admin → MailHog receives an invite email; `/accept-invite` activates the user
- accept-invite is one-shot (second call → 400)

### `iam/admin/*` — Operator admin surface *(all need dev Mongo)*

#### `TenantsAdminControllerIT` — 10 tests
List + create + read + update + delete + provisioning. Asserts:
- `OPERATOR_SUPPORT` can read but cannot create/update/delete (403)
- `OPERATOR_ADMIN` can do everything
- create provisions the per-tenant DB via `TenantDatabaseProvisioner`
- delete deprovisions (drops the per-tenant DB)
- `?q=` performs case-insensitive substring search on name/id
- audit entries are written on every state change

#### `OperatorsAdminControllerIT` — 8 tests
CRUD on operator users, with role gating: `OPERATOR_SUPPORT` reads only, `OPERATOR_ADMIN` mutates. Asserts the bootstrap admin can't be deleted.

#### `OperatorAssignmentsAdminControllerIT` — 6 tests
Grant + revoke + list operator → tenant assignments. Compound-index uniqueness enforced at the API level (duplicate POST → 409).

#### `AuditAdminControllerIT` — 3 tests
- list with filters by actor, action, target
- pagination via `?page=` / `?size=`
- `OPERATOR_SUPPORT` can read

#### `StatsAdminControllerIT` — 2 tests
- `GET /admin/api/stats/overview` returns `{tenants, tenantUsers, pendingInvites}` counters
- numbers update after a tenant + user are created in the same test

#### `AdminTenantUsersControllerIT` — 5 tests
Admin-side tenant-user CRUD without the `switch-tenant` dance — handlers wrap the call in `TenantContext.callIn(pathTenantId, …)`. Asserts:
- `OPERATOR_SUPPORT` can read tenant users
- `OPERATOR_ADMIN` can invite, update, delete
- tenant context is bound for the duration of the request so the per-tenant DB is reachable
- updating a tenant user's role from the admin side also bumps that user's `tv`

#### `TenantSettingsAdminControllerIT` — 9 tests
The extensible settings store via the admin API:
- list every kind for a tenant
- PUT MQTT settings → persisted + redacted on read-back
- PUT DJI settings → persisted + redacted on read-back
- DELETE removes the document
- unknown kind → 404
- `OPERATOR_SUPPORT` can read but not write (403)
- `POST /{kind}/test` runs `ConnectionTester` and returns latency on success
- `POST /{kind}/test` returns failure detail without leaking the secret on bad credentials
- soft-cleanup audit lands even when the cleanup step blips (`try/finally`)

### `iam/tenantadmin/` — Tenant self-service *(all need dev Mongo)*

#### `TenantSelfApiIT` — 14 tests
`/api/tenant/{me,users}` end-to-end. Seeds owner + admin + editor + viewer + a cross-tenant owner via `IamFixtures` and mints tokens via `JwtTestSupport`. Verifies:
- every role can `GET /me` and `GET /users`
- `EDITOR` / `VIEWER` cannot create / update / delete users (403)
- `TENANT_OWNER` / `ADMIN` can invite + update + soft-delete
- update + soft-delete bump the target's `tv` so old tokens stop working immediately
- cross-tenant owner cannot read or write into another tenant (403 from the tenant context guard)
- soft-deleted users no longer appear in `GET /users`
- email-uniqueness check on update returns 409 with the right shape

#### `TenantSettingsControllerIT` — 8 tests
The tenant-side read view of own-tenant settings:
- `GET /api/tenant/settings` lists every kind, redacted
- `GET /api/tenant/settings/{kind}` returns one, redacted
- `EDITOR` / `VIEWER` get 403
- cross-tenant token cannot read another tenant's settings
- writes are not exposed on the tenant side — `PUT /api/tenant/settings/{kind}` returns a 4xx (no controller mapping)
- unknown kind on `GET` returns 404
- response shape matches the admin-side read view (so the SPA can share a model)
- secrets are redacted to the same sentinel on both surfaces

---

## Adding a new test

- **Unit test**: create `src/test/java/.../FooTest.java`. JUnit 5 + AssertJ + (optionally) Mockito are already on the classpath. No `@SpringBootTest` needed for pure-unit work.
- **Lightweight Spring test**: `@SpringBootTest @ActiveProfiles("test")` — no Mongo, no Redis, no Mail. Good for testing controllers + filters.
- **Mongo-backed integration test**: name it `*IT.java`, add `@SpringBootTest @ActiveProfiles("integration") @EnabledIf("…mongoIsReachable")`. Use `@DynamicPropertySource` for the URI; allocate test data with random suffixes; clean up in `@AfterEach`.
- **Architecture rule**: extend `PackageBoundaryTest` or `RepositoryDisciplineTest`. Use full package paths (e.g. `com.orochiverse.platform.tenant..`) to avoid false positives from `common.tenant`.
