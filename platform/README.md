# Platform — Orochiverse multi-tenant backend

Spring Boot 3.5 / Java 25 modular monolith. M1 (IAM + tenancy + email +
deployment) is in. M2 (drone domain) lives under `gcs/` and is currently a
placeholder. See `../docs/superpowers/specs/2026-05-11-platform-shell-m1-design.md`
for the full M1 design.

## Module layout

```
src/main/java/com/orochiverse/platform/
├── PlatformApplication.java
├── common/                   cross-cutting infra
│   ├── audit/                AuditEntry + repository (TTL'd 90 days)
│   ├── data/                 @IamScoped / @TenantScoped marker annotations
│   ├── email/                EmailService + Thymeleaf TEXT-mode renderer
│   ├── migrations/iam/       Mongock changesets for iam_db
│   ├── observability/        RequestIdMdcFilter, AuthMetrics (Micrometer)
│   ├── openapi/              OpenAPI / Swagger config
│   ├── security/
│   │   ├── auth/             JwtAuthenticationFilter, TokenVersionLookup,
│   │   │                     AuthenticatedUser, AuthorityResolver,
│   │   │                     Json* entrypoints, MeController
│   │   ├── jwks/             JwksController
│   │   ├── jwt/              AccessTokenIssuer / Verifier / Claims
│   │   ├── keys/             FileRsaKeyProvider / EphemeralRsaKeyProvider
│   │   ├── passwords/        BCrypt cost-12
│   │   ├── principals/       UserKind / OperatorRole / TenantRole
│   │   └── SecurityConfig.java
│   └── tenant/               TenantContext (ScopedValue),
│                             TenantMongoTemplateRegistry, provisioner
├── iam/                      operator + tenant-user identity (iam_db)
│   ├── admin/                /admin/api/* surface (operators only)
│   │   ├── audit/            AuditAdminController
│   │   ├── operators/        OperatorsAdminController + AssignmentsAdminController
│   │   ├── stats/            StatsAdminController (overview dashboard)
│   │   ├── tenants/          TenantsAdminController
│   │   └── tenantusers/      AdminTenantUsersController
│   ├── auth/                 /api/auth/* — AuthService, controllers,
│   │                         LoginRateLimiter, TokenVersionResolver,
│   │                         RefreshTokenStore, AuthExceptionHandler
│   ├── operators/            OperatorAssignment
│   ├── settings/             extensible tenant_settings store
│   │                         (MqttSettingsHandler, DjiSettingsHandler,
│   │                          ConnectionTester, controller, service)
│   ├── tenantadmin/          /api/tenant/* — TenantMeController,
│   │                         TenantUsersController, TenantSettingsController
│   ├── tenants/              Tenant, TenantStatus, TenantRepository
│   ├── tokens/               SingleUseTokenStore (invite + reset tokens)
│   └── users/                User, UserStatus, UserRepository
└── gcs/                      M2+ placeholder (boundary tests enforce isolation)
```

Module boundaries are enforced by `PackageBoundaryTest` and
`RepositoryDisciplineTest` (ArchUnit). Don't reach across them — the tests
will catch it.

## Quick start

```bash
# 1. Start the dev stack (Mongo 8 / Redis / MailHog)
../scripts/dev-up.sh

# 2. Run the platform app on http://localhost:8080
../scripts/run-app.sh

# Or with remote debug on :5005
../scripts/run-app.sh --debug
```

Default operator (dev only): `admin@orochiverse.local` / `ChangeMe123!` —
auto-seeded by `BootstrapOperatorRunner` on first boot if no operator exists.

See `../scripts/README.md` for the full runner-script reference.

## Building & testing

Requires JDK 25+. Install via SDKMAN:

```bash
curl -s "https://get.sdkman.io" | bash
sdk install java 25-tem
```

Or via Homebrew, or drop a JDK 25 tarball at `~/bin/jdk-25.x/`. The runner
scripts auto-resolve `JAVA_HOME` from any of these locations.

```bash
./mvnw test                       # 199 unit tests (~30s, no infra needed)
../scripts/dev-up.sh && ./mvnw verify   # + 113 integration tests
```

Mongo-backed ITs use `@EnabledIf("MongoTestSupport#mongoIsReachable")` —
they skip themselves when the dev stack is down so `mvn verify` stays
green either way.

`mvn clean install` auto-formats Java sources via Spotless before compile —
you should never see formatting-only changes in PR diffs.

## What's available out of the box

| Surface | URL |
|---|---|
| Swagger UI (every endpoint tagged) | `/swagger-ui.html` |
| Health | `/actuator/health` |
| JWKS | `/.well-known/jwks.json` |
| Prometheus scrape (requires auth) | `/actuator/prometheus` |
| Postman collection | `../docs/postman/` |

## Remote debugging

```bash
../scripts/run-app.sh --debug              # JDWP on $APP_DEBUG_PORT (default 5005)
../scripts/run-app.sh --debug --suspend    # JVM waits for debugger before booting
```

IntelliJ: **Run → Edit Configurations → + → Remote JVM Debug** → host
`localhost`, port `5005`. See `../scripts/README.md` for VS Code setup.

## M1 status

| Phase | Title | Status |
|---|---|---|
| 1.1 | Repo & build skeleton | ✅ |
| 1.2 | Mongo 8 dev env + Spotless + runner scripts | ✅ |
| 1.3 | Multi-tenant Mongo wiring | ✅ |
| 1.4 | IAM data model + Mongock indexes | ✅ |
| 1.5 | JWT, JWKS, password hashing | ✅ |
| 1.6 | Security filter chain + tenant context | ✅ |
| 1.7 | Auth + operator-admin APIs | ✅ |
| 1.8 | Tenant-admin APIs | ✅ |
| 1.9 | Email service + invite-accept + password reset | ✅ |
| 1.10 | Observability + auth hardening | ✅ |
| 1.11 | Testing foundation (`testsupport/`) | ✅ |
| 1.12 | CI + Dockerfile + OpenAPI hardening | ✅ |
| + | Four admin-console gap fills + six review fixes | ✅ |
| + | GHCR pipeline + prod compose + deployment doc | ✅ |

**Test totals: 312 (199 unit + 113 integration), all green.**

See [`../docs/features/roadmap.md`](../docs/features/roadmap.md) for what's
next (M1.5 frontend, M2 drone domain, M3+ hardening).
