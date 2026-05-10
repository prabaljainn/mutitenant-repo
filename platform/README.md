# Platform — Orochiverse Multi-Tenant Backend

Spring Boot 3.5 / Java 25 modular monolith. IAM today; GCS modules folded in
during M2+. See `../docs/superpowers/specs/2026-05-11-platform-shell-m1-design.md`
for the full design.

## Module layout

```
src/main/java/com/orochiverse/platform/
├── PlatformApplication.java
├── common/        cross-cutting infra: security, tenant context, mongo, audit
├── iam/           operator-side: tenants, operator users, assignments
└── tenant/        tenant-admin self-service: managing the tenant's own users
```

Module boundaries are enforced by `PackageBoundaryTest` (ArchUnit). Don't
reach across them.

## Building & testing

### With local Java (preferred for fast iteration)

Requires JDK 25+. Install via SDKMAN:

```bash
curl -s "https://get.sdkman.io" | bash
sdk install java 25-tem
```

Then:

```bash
./mvnw verify
```

### With Docker (no local Java required)

```bash
docker compose -f docker-compose.build.yml build test
docker compose -f docker-compose.build.yml run --rm test
```

The image runs `./mvnw verify` inside an `eclipse-temurin:25-jdk` container
and caches the Maven repo to a named volume so re-runs are fast.

## Phase 1.1 verification

After running the test suite, you should see:

- `ApplicationBootSmokeTest` — Spring context loads + `/actuator/health` returns `UP`
- `PackageBoundaryTest` — five ArchUnit boundary rules all green

## Roadmap

| Phase | Title | Status |
|---|---|---|
| 1.1 | Repo & build skeleton | ✓ |
| 1.2 | Local Docker dev environment (Mongo + Redis + Mailhog) | next |
| 1.3 | Multi-tenant Mongo wiring (critical path) | |
| 1.4 | IAM data model + Mongock indexes | |
| 1.5 | JWT, JWKS, password hashing | |
| 1.6 | Security filter chain + tenant context | |
| 1.7 | Operator admin APIs | |
| 1.8 | Tenant-admin APIs | |
| 1.9 | Email service | |
| 1.10 | Observability + audit | |
| 1.11 | Testing foundation (Testcontainers) | |
| 1.12 | CI / Dockerfile / OpenAPI hardening | |
