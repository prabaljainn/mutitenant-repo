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
| `logging.pattern.console` | `… [%X{requestId},%X{userId},%X{tenantId}] …` | MDC fields populated by `RequestIdMdcFilter` + `JwtAuthenticationFilter`. |
| `springdoc.api-docs.path` | `/v3/api-docs` | OpenAPI JSON. |
| `springdoc.swagger-ui.path` | `/swagger-ui.html` | Swagger UI. |
| `mongock.enabled` | `true` | Mongock active. |
| `mongock.runner-type` | `initializingbean` | Run as a bean lifecycle event (not via the Spring Boot starter's app runner). |
| `mongock.transactional` | `false` | Single-node RS supports transactions but indexes don't need them. |
| `mongock.migration-scan-package` | `[com.orochiverse.platform.common.migrations.iam]` | Where `@ChangeUnit` classes live. |
| `platform.security.jwt.issuer` | `https://iam.orochiverse.com` | `iss` claim. Verifier rejects tokens with any other issuer. |
| `platform.security.jwt.access-token-ttl` | `PT15M` | 15-minute access tokens (spec §5.1). |
| `platform.security.jwt.clock-skew` | `PT30S` | Tolerance applied to `iat` / `exp` checks. |
| `platform.email.from` | `noreply@orochiverse.local` | Default `From:` header. Overridden in prod. |
| `platform.email.reply-to` | `support@orochiverse.local` | Optional `Reply-To:`. Empty disables it. |
| `platform.email.base-url` | `http://localhost:8080` | URL stem for invite + reset links rendered into emails. |

JWT key paths + `key-id` are deliberately NOT set in `application.yml` — leaving them unset selects the `EphemeralRsaKeyProvider`. Set them only in `application-prod.yml`.

---

## `application-dev.yml`

| Property | Value | Notes |
|---|---|---|
| `spring.data.mongodb.uri` | `mongodb://localhost:27017/iam_db?replicaSet=rs0&directConnection=true` | Hits the `dev-up.sh` Mongo container. |
| `spring.data.redis.host` / `.port` | `localhost` / `6379` | Refresh tokens + login buckets. |
| `spring.mail.host` / `.port` | `localhost` / `1025` | MailHog. |
| `platform.bootstrap.operator.email` | `admin@orochiverse.local` | Dev-only auto-seeded operator. |
| `platform.bootstrap.operator.password` | `ChangeMe123!` | Dev-only. Unset in prod profile. |
| `platform.settings.allow-private-test-targets` | `true` | **Dev only.** Lets the connection tester reach localhost (MailHog, your local MQTT broker). Disables the SSRF guard — never set in prod. |

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
| `spring.data.mongodb.uri` | (set per-test via `@DynamicPropertySource`) | Each test can target its own DB / connection without stomping on others. |
| `logging.level.root` | `WARN` | |
| `logging.level.com.orochiverse.platform` | `INFO` | |
| `logging.level.org.mongodb.driver` | `WARN` | Suppress the noisy driver chatter. |

---

## `application-prod.yml`

Production reads everything from environment variables. Missing vars cause Spring to fail-fast on startup (`${VAR:?…must be set in production}`).

| Property | Env var | Notes |
|---|---|---|
| `spring.data.mongodb.uri` | `MONGODB_URI` | Full connection string with replica-set + auth. |
| `spring.redis.url` | `REDIS_URL` | Refresh tokens + rate-limit buckets. |
| `spring.mail.host` | `SMTP_HOST` | SMTP relay (SendGrid, SES, Postmark, …). |
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
| `platform.email.from` | `PLATFORM_EMAIL_FROM` | Required. Must be a domain you've SPF/DKIM-aligned. |
| `platform.email.reply-to` | `PLATFORM_EMAIL_REPLY_TO` | Optional. |
| `platform.email.base-url` | `PLATFORM_EMAIL_BASE_URL` | Required. e.g. `https://api.example.com`. |

Set the JWT key vars and the `FileRsaKeyProvider` activates instead of the ephemeral one.

### Bootstrap operator (first-boot creds)

| Property | Env var | Notes |
|---|---|---|
| `platform.bootstrap.operator.email` | `PLATFORM_BOOTSTRAP_OPERATOR_EMAIL` | If set with password, `BootstrapOperatorRunner` seeds an `OPERATOR_ADMIN` on first boot only (no-op once an operator exists). |
| `platform.bootstrap.operator.password` | `PLATFORM_BOOTSTRAP_OPERATOR_PASSWORD` | Same. Rotate immediately via the admin API after first login and unset these vars. |

### Tunables (rarely changed)

| Property | Default | Where |
|---|---|---|
| `platform.security.refresh-token-ttl` | `P30D` | `InMemoryRefreshTokenStore` — refresh-token sliding window. |
| `platform.security.tv-cache.ttl` | `PT30S` | `TokenVersionResolver` — Caffeine TTL for `tv` lookups. |
| `platform.settings.allow-private-test-targets` | `false` | `ConnectionTester` — flip ONLY in dev to allow localhost / RFC1918 test targets. |

---

## Dev stack env (`deployment/.env.example`)

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

## Prod stack env (`deployment/prod/.env.example`)

`deployment/prod/docker-compose.yml` reads `deployment/prod/.env`. Copy from `.env.example` and fill in real values. **Never commit `.env`** — it contains every secret.

| Var | Purpose |
|---|---|
| `PLATFORM_HOST` | Public FQDN. Must have an A/AAAA record pointing at this host (TLS-ALPN-01 needs it). |
| `LETSENCRYPT_EMAIL` | Account email for Let's Encrypt expiry notices. |
| `PLATFORM_IMAGE` / `PLATFORM_IMAGE_TAG` | GHCR image coordinates. Pin a tag for zero-downtime deploys. |
| `MONGO_ROOT_USERNAME` / `MONGO_ROOT_PASSWORD` | Root creds, used only by `mongo-init` to bootstrap the app user. |
| `MONGO_APP_USERNAME` / `MONGO_APP_PASSWORD` | The credentials the platform connects with. |
| `REDIS_PASSWORD` | `--requirepass` for Redis. |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USERNAME` / `SMTP_PASSWORD` | Outbound mail relay. |
| `PLATFORM_EMAIL_FROM` / `PLATFORM_EMAIL_REPLY_TO` | Visible `From:` / `Reply-To:` headers. |
| `PLATFORM_JWT_KEY_ID` | The `kid` you copied from `scripts/gen-jwt-keys.sh` output. |
| `BOOTSTRAP_OPERATOR_EMAIL` / `BOOTSTRAP_OPERATOR_PASSWORD` | First-boot operator. Optional; remove from the env after first login. |

All signing material is generated automatically on first boot by
one-shot init services into docker-managed volumes — no host-side
keygen step is required:

| Volume | Generated by | Contents |
|---|---|---|
| `jwt_keys` | `jwt-keys-init` | RSA-2048 keypair (PKCS#8 / X.509) + `kid.txt` |
| `mongodb_keyfile` | `mongo-keyfile-init` | rs0 internal-auth keyfile, mode 400, uid 999 |

The volumes persist across `down/up`; rotation happens on `down -v`.
`scripts/gen-jwt-keys.sh` and `scripts/gen-mongo-keyfile.sh` are
preserved as manual escape hatches for operators who want to pre-seed
the volumes themselves — see `docs/deployment.md` for the recipes.

---

## Maven properties (`platform/pom.xml`)

Pinned versions for the toolchain + libraries. Bump in one place.

| Property | Value | Why pinned |
|---|---|---|
| `java.version` / `release` | `25` | LTS. ZGC + virtual threads. |
| `jjwt.version` | `0.12.6` | First version with the `Jwks` builder API we use. |
| `springdoc.version` | `2.6.0` | Spring Boot 3.5 compat. |
| `mongock.version` | `5.5.1` | Latest with `mongock-springboot-v3` driver. |
| `caffeine.version` | `3.1.8` | `TokenVersionResolver` cache. |
| `archunit.version` | `1.4.1` | First version that reads Java 25 class files. |
| `testcontainers.version` | `1.21.3` | (Pinned but not in the active path; Mongo ITs use the local dev stack instead.) |
| `spotless.version` | `2.44.5` | Light-touch formatter (no Java-25-incompatible reformatter). |

Spring Boot version comes from the parent pom: `3.5.0`.

---

## Things deliberately NOT configurable

- **BCrypt cost** — pinned at 12 in `PasswordHashing.BCRYPT_COST`. Lowering requires an ADR.
- **JWT signing algorithm** — RS256, hard-coded in `AccessTokenIssuer` (`Jwts.SIG.RS256`) and `JwksController` (`alg: RS256`).
- **JWKS cache TTL** — 1 hour, hard-coded in `JwksController`.
- **Login rate-limit window** — 5 attempts per `(email, ip)` per 15-minute sliding window, hard-coded in `LoginRateLimiter`.
- **Audit-log TTL** — 90 days, set in `IamBaselineIndexes` via the `expiresAt` TTL index.
- **Tenant ID format** — `^[a-z0-9][a-z0-9_-]{0,49}$`, in `TenantId.PATTERN`. Changing this requires a migration since DB names depend on it.
- **Tenant-DB naming** — `tenant_<id>_db`, in `TenantId.dbName`. Same.
