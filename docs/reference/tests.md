# Test reference

Every test class in `platform/src/test/java`, what it verifies, and how to run it.

**Total: 247 tests** — 159 unit (Surefire) + 88 integration (Failsafe).

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

`.github/workflows/build.yml` runs `./mvnw verify` on every push to `main` and every PR. Surefire (159 tests) runs in full; Failsafe ITs that need Mongo skip themselves on the GitHub runner via the `@EnabledIf` gate, so the build stays green without standing up a dev stack in CI. Test reports are uploaded as an artifact when the job fails. To exercise the Mongo-backed ITs end-to-end, run `./scripts/dev-up.sh && ./mvnw verify` locally.

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

---

## Adding a new test

- **Unit test**: create `src/test/java/.../FooTest.java`. JUnit 5 + AssertJ + (optionally) Mockito are already on the classpath. No `@SpringBootTest` needed for pure-unit work.
- **Lightweight Spring test**: `@SpringBootTest @ActiveProfiles("test")` — no Mongo, no Redis, no Mail. Good for testing controllers + filters.
- **Mongo-backed integration test**: name it `*IT.java`, add `@SpringBootTest @ActiveProfiles("integration") @EnabledIf("…mongoIsReachable")`. Use `@DynamicPropertySource` for the URI; allocate test data with random suffixes; clean up in `@AfterEach`.
- **Architecture rule**: extend `PackageBoundaryTest` or `RepositoryDisciplineTest`. Use full package paths (e.g. `com.orochiverse.platform.tenant..`) to avoid false positives from `common.tenant`.
