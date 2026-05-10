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

## Quick start

```bash
# 1. Start the dev stack (Mongo 8 / Redis / Mailhog)
../scripts/dev-up.sh

# 2. Run the platform app on http://localhost:8080
../scripts/run-app.sh

# Or with remote debug on :5005
../scripts/run-app.sh --debug
```

See `../scripts/README.md` for the full runner-script reference.

## Building & testing

### With local Java (preferred for fast iteration)

Requires JDK 25+. Install via SDKMAN:

```bash
curl -s "https://get.sdkman.io" | bash
sdk install java 25-tem
```

Or via Homebrew:

```bash
brew install openjdk@25
```

Then:

```bash
# Unit tests + boundary checks (fast, no infra)
./mvnw test

# Full verify: also runs integration tests
# (skipped automatically if dev stack isn't up; start it first
#  for the IT to actually run)
../scripts/dev-up.sh && ./mvnw verify
```

`mvn clean install` auto-formats Java sources via Spotless before compile —
import order, trailing whitespace, final newlines. **You should never see
formatting-only changes in PR diffs.**

### With Docker (no local Java required)

```bash
docker compose -f docker-compose.build.yml build test
docker compose -f docker-compose.build.yml run --rm test
```

Runs `./mvnw verify` inside an `eclipse-temurin:25-jdk` container and caches
the Maven repo to a named volume so re-runs are fast.

## Phase 1.2 verification

With the dev stack up (`../scripts/dev-up.sh`), `./mvnw verify` should report:

- `ApplicationBootSmokeTest` — 2 tests (context loads, `/actuator/health` UP)
- `PackageBoundaryTest` — 5 tests (all module boundaries enforced)
- `MongoConnectivityIT` — 3 tests (real Mongo 8 replica set, write/read,
  rs0 PRIMARY)

→ **10 tests, 0 failures, 0 errors**

If the dev stack isn't up, the IT skips gracefully (build still green).

## Remote debugging

```bash
../scripts/run-app.sh --debug              # JDWP on $APP_DEBUG_PORT (default 5005)
../scripts/run-app.sh --debug --suspend    # JVM waits for debugger before booting
```

In IntelliJ: **Run → Edit Configurations → + → Remote JVM Debug** → host
`localhost`, port `5005`. See `../scripts/README.md` for VS Code setup.

## Roadmap

| Phase | Title | Status |
|---|---|---|
| 1.1 | Repo & build skeleton | ✓ |
| 1.2 | Mongo 8 dev env + Spotless + runner scripts + Mongo IT | ✓ |
| 1.3 | Multi-tenant Mongo wiring (critical path) | next |
| 1.4 | IAM data model + Mongock indexes | |
| 1.5 | JWT, JWKS, password hashing | |
| 1.6 | Security filter chain + tenant context | |
| 1.7 | Operator admin APIs | |
| 1.8 | Tenant-admin APIs | |
| 1.9 | Email service | |
| 1.10 | Observability + audit | |
| 1.11 | Testing foundation (Testcontainers when docker-java catches up) | |
| 1.12 | CI / Dockerfile / OpenAPI hardening | |
