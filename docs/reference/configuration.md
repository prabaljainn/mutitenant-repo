# Configuration reference

Every settable property and environment variable, organized by where it's bound and which profile uses it.

YAML location: `platform/src/main/resources/`.

---

## `application.yml` (default — applies to every profile unless overridden)

| Property | Value | Why |
|---|---|---|
| `spring.application.name` | `platform` | Surfaces in logs, metrics tags. |
| `spring.threads.virtual.enabled` | `true` | Java 25 virtual threads for the request thread pool. |
| `spring.profiles.default` | `dev` | When no `SPRING_PROFILES_ACTIVE` is set, run as `dev`. |
| `spring.autoconfigure.exclude` | `UserDetailsServiceAutoConfiguration` | We do JWT, not Spring's default user/password. Excluding silences the "Using generated security password" log. |
| `spring.data.mongodb.auto-index-creation` | `false` | Indexes are managed by Mongock — Spring Data must NOT auto-create them or they'd be untracked. |
| `server.port` | `8080` | HTTP listener. |
| `server.shutdown` | `graceful` | Drain in-flight requests on SIGTERM. |
| `server.forward-headers-strategy` | `framework` | Trust `X-Forwarded-*` from the upstream proxy (Traefik). |
| `management.endpoints.web.exposure.include` | `health,info,metrics,prometheus` | Actuator surface. |
| `management.endpoint.health.show-details` | `when-authorized` | Anonymous callers get just `UP`/`DOWN`; authenticated callers see component detail. |
| `management.endpoint.health.probes.enabled` | `true` | Enables `/actuator/health/liveness` + `/readiness` for K8s. |
| `management.metrics.tags.application` | `${spring.application.name}` | Every metric is tagged `application=platform`. |
| `logging.level.root` | `INFO` | |
| `logging.level.com.orochiverse.platform` | `DEBUG` (dev) | Bumped to `INFO` in prod. |
| `logging.pattern.console` | `… [%X{requestId},%X{userId},%X{tenantId}] …` | MDC fields populated by Phase 1.10's request filter. |
| `springdoc.api-docs.path` | `/v3/api-docs` | OpenAPI JSON. |
| `springdoc.swagger-ui.path` | `/swagger-ui.html` | Swagger UI. |
| `mongock.enabled` | `true` | Mongock active. |
| `mongock.runner-type` | `initializingbean` | Run as a bean lifecycle event (not via the Spring Boot starter's app runner). |
| `mongock.transactional` | `false` | Single-node RS supports transactions but indexes don't need them. |
| `mongock.migration-scan-package` | `[com.orochiverse.platform.common.migrations.iam]` | Where `@ChangeUnit` classes live. |
| `platform.security.jwt.issuer` | `https://iam.orochiverse.com` | `iss` claim. Verifier rejects tokens with any other issuer. |
| `platform.security.jwt.access-token-ttl` | `PT15M` | 15-minute access tokens (spec §5.1). |
| `platform.security.jwt.clock-skew` | `PT30S` | Tolerance applied to `iat` / `exp` checks. |

JWT key paths + `key-id` are deliberately NOT set in `application.yml` — leaving them unset selects the `EphemeralRsaKeyProvider`. Set them only in `application-prod.yml`.

---

## `application-dev.yml`

| Property | Value | Notes |
|---|---|---|
| `spring.data.mongodb.uri` | `mongodb://localhost:27017/iam_db?replicaSet=rs0&directConnection=true` | Hits the `dev-up.sh` Mongo container. |

---

## `application-test.yml`

Used by Surefire unit tests + lightweight `@SpringBootTest @ActiveProfiles("test")` ITs.

| Property | Value | Notes |
|---|---|---|
| `spring.autoconfigure.exclude` | (list — see file) | Excludes Mongo/Mongo data/Mongo repos, Redis, Redis repos, Mail, and `UserDetailsServiceAutoConfiguration`. **No infra deps.** |
| `mongock.enabled` | `false` | No Mongock without Mongo. |
| `logging.level.root` | `WARN` | Quieter test output. |
| `logging.level.com.orochiverse.platform` | `INFO` | |

---

## `application-integration.yml`

Used by Failsafe ITs that need Mongo (`@ActiveProfiles("integration")`).

| Property | Value | Notes |
|---|---|---|
| `spring.autoconfigure.exclude` | Redis, Redis repos, Mail, `UserDetailsServiceAutoConfiguration` | **Mongo IS enabled here.** |
| `spring.data.mongodb.uri` | (set per-test via `@DynamicPropertySource`) | So each test can target its own DB / connection without stomping on others. |
| `logging.level.root` | `WARN` | |
| `logging.level.com.orochiverse.platform` | `INFO` | |
| `logging.level.org.mongodb.driver` | `WARN` | Suppress the noisy driver chatter. |

---

## `application-prod.yml`

Production reads everything from environment variables. Missing vars cause Spring to fail-fast on startup (`${VAR:?…must be set in production}`).

| Property | Env var | Notes |
|---|---|---|
| `spring.data.mongodb.uri` | `MONGODB_URI` | Full connection string with replica-set + auth. |
| `spring.redis.url` | `REDIS_URL` | Phase 1.10. |
| `spring.mail.host` | `SMTP_HOST` | Phase 1.9. |
| `spring.mail.port` | `SMTP_PORT` (defaults to `587`) | |
| `spring.mail.username` | `SMTP_USERNAME` | |
| `spring.mail.password` | `SMTP_PASSWORD` | |
| `spring.mail.properties.mail.smtp.starttls.enable` | `true` | |
| `server.forward-headers-strategy` | `framework` | |
| `management.endpoint.health.show-details` | `never` | Don't leak component state to anonymous probes. |
| `logging.level.root` / `com.orochiverse.platform` | `INFO` | No DEBUG in prod. |
| `platform.security.jwt.issuer` | `PLATFORM_JWT_ISSUER` | Required. |
| `platform.security.jwt.private-key-path` | `PLATFORM_JWT_PRIVATE_KEY_PATH` | Required. PKCS#8 PEM. |
| `platform.security.jwt.public-key-path` | `PLATFORM_JWT_PUBLIC_KEY_PATH` | Required. X.509 PEM. |
| `platform.security.jwt.key-id` | `PLATFORM_JWT_KEY_ID` | Required. Identifies the active key in the JWKS. |

Set the JWT key vars and the `FileRsaKeyProvider` activates instead of the ephemeral one.

---

## Docker / dev stack env (`deployment/.env.example`)

`./scripts/dev-up.sh` reads `deployment/.env`. Copy `.env.example` to `.env` and edit.

| Var | Default | What it controls |
|---|---|---|
| `MONGO_VERSION` | `8.0` | Mongo image tag. |
| `MONGO_PORT` | `27017` | Host port mapping. |
| `REDIS_VERSION` | `7.4-alpine` | |
| `REDIS_PORT` | `6379` | |
| `MAILHOG_SMTP_PORT` | `1025` | |
| `MAILHOG_UI_PORT` | `8025` | |
| `JAVA_OPTS` | `-Xms256m -Xmx1g -XX:+UseZGC -XX:+ZGenerational --enable-preview` | Picked up by `run-app.sh`. |
| `TRAEFIK_PORT` | `80` | Only relevant when started with `--profile proxy`. |

---

## Maven properties (`platform/pom.xml`)

Pinned versions for the toolchain + libraries. Bump in one place.

| Property | Value | Why pinned |
|---|---|---|
| `java.version` / `release` | `25` | LTS. ZGC + virtual threads. |
| `jjwt.version` | `0.12.6` | First version with the `Jwks` builder API we use. |
| `springdoc.version` | `2.6.0` | Spring Boot 3.5 compat. |
| `mongock.version` | `5.5.1` | Latest with `mongock-springboot-v3` driver. |
| `archunit.version` | `1.4.1` | First version that reads Java 25 class files. |
| `testcontainers.version` | `1.21.3` | (Pinned but not in the active path; Mongo ITs use the local dev stack instead.) |
| `spotless.version` | `2.44.5` | Light-touch formatter (no Java-25-incompatible reformatter). |

Spring Boot version comes from the parent pom: `3.5.0`.

---

## Things deliberately NOT configurable

- **BCrypt cost** — pinned at 12 in `PasswordHashing.BCRYPT_COST`. Lowering requires an ADR.
- **JWT signing algorithm** — RS256, hard-coded in `AccessTokenIssuer` (`Jwts.SIG.RS256`) and `JwksController` (`alg: RS256`).
- **JWKS cache TTL** — 1 hour, hard-coded in `JwksController`.
- **Tenant ID format** — `^[a-z0-9][a-z0-9_-]{0,49}$`, in `TenantId.PATTERN`. Changing this requires a migration since DB names depend on it.
- **Tenant-DB naming** — `tenant_<id>_db`, in `TenantId.dbName`. Same.
