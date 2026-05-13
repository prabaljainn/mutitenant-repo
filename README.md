# Orochiverse multi-tenant platform

A Spring Boot + MongoDB platform that hosts multiple customer tenants from a
single codebase. **M1 complete** — IAM, multi-tenancy, auth, email, operator +
tenant self-service, observability, deployment pipeline. See
[`docs/features/roadmap.md`](docs/features/roadmap.md) for what's next.

## Repository layout

```
mutitenant-repo/
├── platform/              Spring Boot application
│   ├── src/main/java/…    common, iam (admin, auth, settings, tenantadmin, …)
│   ├── src/test/java/…    312 tests (199 unit + 113 integration)
│   ├── Dockerfile         Multi-stage, multi-arch, non-root, HEALTHCHECK
│   └── pom.xml
├── deployment/
│   ├── docker-compose.yml      Dev stack (Mongo 8 rs0, Redis, MailHog, Traefik)
│   └── prod/                   Prod stack (Mongo+auth, Redis+auth, Traefik+Let's Encrypt)
├── scripts/                    dev-up / dev-down / run-app / gen-jwt-keys / gen-mongo-keyfile
├── docs/                       Architecture, references, deployment, roadmap
└── .github/workflows/          build.yml (CI), release.yml (GHCR image publish)
```

## Tech stack

| Layer | Choice | Notes |
|---|---|---|
| Runtime | **Java 25** (LTS) + virtual threads + ZGC | |
| Framework | **Spring Boot 3.5** | `@EnableMethodSecurity`, modular monolith |
| Database | **MongoDB 8.0** | single-node `rs0` replica set; **database per tenant** |
| Cache / state | **Redis 7.4** | refresh tokens, rate-limit buckets |
| Auth | JWT (RS256) + JWKS, BCrypt cost 12 | `tokenVersion` revocation, per-(email, IP) rate limit |
| Email | Spring Mail + Thymeleaf (TEXT mode) | MailHog locally, SMTP relay in prod |
| Build | Maven 3.9, Spotless | one-pom, layered Boot jar |
| Containers | Multi-arch Docker (amd64 + arm64) | image published to GHCR |
| Proxy (prod) | Traefik v3 + Let's Encrypt | TLS-ALPN-01 |
| Observability | Micrometer + Prometheus + MDC | `requestId`, `userId`, `tenantId` per log line |

## Quickstart

```bash
# Dev: bring up the local stack + run the app
./scripts/dev-up.sh                    # Mongo, Redis, MailHog
cd platform && ../scripts/run-app.sh   # auto-resolves JDK 25
```

The default operator is seeded on first boot — log in at `http://localhost:8080/swagger-ui.html` with `admin@orochiverse.local` / `ChangeMe123!`.

| Dev surface | Where |
|---|---|
| Swagger UI | <http://localhost:8080/swagger-ui.html> |
| Health | <http://localhost:8080/actuator/health> |
| JWKS | <http://localhost:8080/.well-known/jwks.json> |
| MailHog (captures outbound email) | <http://localhost:8025> |
| Postman collection + environment | [`docs/postman/`](docs/postman/) |

## Tests

```bash
./mvnw test          # 199 unit tests
./mvnw verify        # + 113 integration tests (Mongo-backed ITs skip if dev stack is down)
```

## Deployment

**Image pipeline**: every push to `main` publishes a multi-arch image to GHCR
as `ghcr.io/<owner>/<repo>/platform:latest`. Tag a `v1.2.3` to mint a semver
release. See `.github/workflows/release.yml`.

**Production stack**: single-VPS docker-compose with Mongo + Redis + Traefik
and automatic Let's Encrypt. Six commands from clone to a TLS-terminated API
on a public hostname. End-to-end in [`docs/deployment.md`](docs/deployment.md).

## Documentation

| | |
|---|---|
| [Deployment](docs/deployment.md) | VPS + docker-compose + GHCR pipeline, end-to-end. |
| [Roadmap](docs/features/roadmap.md) | M1 status, M1.5 frontend, M2 drone domain, M3+ hardening. |
| [System overview](docs/architecture/01-system-overview.md) | High-level architecture, modules. |
| [Multi-tenancy design](docs/architecture/02-multitenancy-design.md) | Tenancy strategy, `TenantContext`, per-tenant DB. |
| [Authentication design](docs/architecture/03-authentication-design.md) | JWT, JWKS, RBAC, password reset, rate limiting. |
| [Email service](docs/architecture/04-email-service-design.md) | Templates, providers, async. |
| [API integration](docs/architecture/05-api-integration-design.md) | Spec-level cross-API rules. |
| [Class reference](docs/reference/classes.md) | Every production class, by package. |
| [Runtime / services reference](docs/reference/services.md) | What runs where, ports, profiles. |
| [Roles + endpoint matrix](docs/reference/roles.md) | UserKind / OperatorRole / TenantRole + RBAC table. |
| [Configuration reference](docs/reference/configuration.md) | Every env var + Spring property. |
| [Test reference](docs/reference/tests.md) | Every test class, what it asserts. |
| [Postman](docs/postman/README.md) | Importable collection + environment. |

## Issue tracking

[GCS-POC on Linear](https://linear.app/norl/team/GCS/all).
