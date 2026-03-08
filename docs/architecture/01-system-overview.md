# System Overview — Multitenant Platform

## 1. Vision

A **multitenant SaaS platform** built with **Spring Boot** and **MongoDB** that provides:

| Surface | URL Pattern | Purpose |
|---------|-------------|---------|
| **Admin Console** | `admin.domain.com` _or_ `domain.com/admin` | Super-admin / admin dashboard — tenant lifecycle, user management, role management, system-wide emails |
| **Tenant Platform** | `domain.com` (tenant resolved via auth context) | Single-tenant experience for end-users belonging to that tenant |

---

## 2. High-Level Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                        CLIENTS                                    │
│   admin.domain.com          domain.com                           │
│   (Admin SPA)               (Tenant SPA)                         │
└──────────┬─────────────────────┬─────────────────────────────────┘
           │  HTTPS              │  HTTPS
           ▼                     ▼
┌──────────────────────────────────────────────────────────────────┐
│                   REVERSE PROXY / GATEWAY                        │
│                        (Traefik v3)                              │
│   ┌─────────────┐        ┌──────────────────┐                   │
│   │ /admin/**    │        │ /api/**           │                   │
│   │ routes       │        │ routes            │                   │
│   └──────┬──────┘        └────────┬──────────┘                   │
└──────────┼─────────────────────────┼─────────────────────────────┘
           │                         │
           ▼                         ▼
┌──────────────────────────────────────────────────────────────────┐
│                    SPRING BOOT APPLICATION                        │
│                                                                   │
│  ┌────────────────┐  ┌────────────────┐  ┌────────────────────┐  │
│  │ Admin Module   │  │ Platform Module│  │ Common Module      │  │
│  │ - Tenant CRUD  │  │ - Tenant-scoped│  │ - Auth (JWT/OAuth) │  │
│  │ - User Mgmt    │  │   business     │  │ - API Key Auth     │  │
│  │ - Role Mgmt    │  │   logic        │  │ - Email Service    │  │
│  │ - API Key Mgmt │  │ - Tenant       │  │ - Audit Logging    │  │
│  │ - System Config│  │   dashboard    │  │ - Tenant Resolver  │  │
│  │                │  │                │  │ - Exception Handler│  │
│  └────────────────┘  └────────────────┘  └────────────────────┘  │
│                                                                   │
│  ┌─────────────────────────────────────────────────────────────┐  │
│  │              DATA ACCESS LAYER (Spring Data MongoDB)        │  │
│  │         Tenant-aware repositories / filters                 │  │
│  └─────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────┘
           │               │                │
           ▼               ▼                ▼
     ┌──────────┐   ┌──────────┐     ┌──────────────┐
     │ MongoDB  │   │  Redis   │     │ SMTP / Email │
     │ (data)   │   │ (cache,  │     │  Provider    │
     │          │   │  sessions│     │ (SendGrid /  │
     │          │   │  tokens) │     │  SES / SMTP) │
     └──────────┘   └──────────┘     └──────────────┘
```

---

## 3. Technology Stack

| Layer | Technology | Why |
|-------|-----------|-----|
| **Backend** | Spring Boot 3.x (Java 21) | Production-proven, vast ecosystem, strong security stack |
| **Database** | MongoDB 7.x | Flexible schema for multi-tenancy, native support for discriminator-based tenancy |
| **Caching / Sessions** | Redis 7.x | Token blacklisting, rate-limiting, session store |
| **Auth** | Spring Security + JWT + OAuth2 | Industry standard; supports MFA, social login extensibility |
| **Email** | Spring Mail + Thymeleaf templates | Template-based emails; pluggable providers (SendGrid, AWS SES, SMTP) |
| **API Docs** | SpringDoc OpenAPI (Swagger) | Auto-generated, always in sync with code |
| **Build** | Maven | Industry standard for Spring Boot; simple XML config, excellent IDE support |
| **Containerization** | Docker + Docker Compose | Consistent dev/prod parity |
| **Reverse Proxy** | Traefik v3 | Auto-discovery via Docker labels, easy SSL (Let's Encrypt), dashboard UI |

---

## 4. Repository Structure

```
mutitenant-repo/
├── deployment/                    # Everything needed to deploy services
│   ├── docker-compose.yml         # All infra services (MongoDB, Redis, Mailhog, Traefik)
│   ├── docker-compose.prod.yml    # Production overrides
│   ├── mongodb/
│   │   ├── init-scripts/          # Mongo init JS (create users, indexes)
│   │   └── mongod.conf            # Custom Mongo config
│   ├── redis/
│   │   └── redis.conf
│   ├── traefik/
│   │   ├── traefik.yml            # Static config (entrypoints, providers)
│   │   ├── dynamic/               # Dynamic config (routes, middlewares)
│   │   └── acme/                  # Let's Encrypt certs (gitignored)
│   ├── mailhog/                   # Dev email testing
│   └── .env.example               # Template for env vars
│
├── platform/                      # Spring Boot application (the actual code)
│   ├── pom.xml
│   ├── mvnw / mvnw.cmd            # Maven wrapper
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/kloudspot/platform/
│   │   │   │   ├── PlatformApplication.java
│   │   │   │   ├── common/        # Shared: auth, API key auth, email, tenant resolver
│   │   │   │   ├── admin/         # Admin module: tenant/user/role/API key CRUD
│   │   │   │   └── tenant/        # Tenant-scoped module: business logic
│   │   │   └── resources/
│   │   │       ├── application.yml
│   │   │       ├── application-dev.yml
│   │   │       ├── application-prod.yml
│   │   │       └── templates/     # Email templates (Thymeleaf)
│   │   └── test/
│   └── Dockerfile
│
├── docs/                          # All official documentation
│   ├── architecture/              # Architecture decision records & diagrams
│   ├── features/                  # Feature specs, backlog, roadmap
│   ├── api/                       # API contracts, Postman collections
│   ├── deployment/                # Deployment guides
│   └── decisions/                 # ADRs (Architecture Decision Records)
│
├── .gitignore
└── README.md
```

> **Key principle:** `deployment/` is infrastructure-only (Docker, configs, scripts). `platform/` is the Spring Boot application code. They can be built and operated independently.

---

## 5. Module Breakdown

### 5.1 `common/` — Shared Foundation

| Package | Responsibility |
|---------|---------------|
| `common.security` | JWT generation/validation, API key auth filter, Spring Security config |
| `common.tenant` | `TenantContext` (ThreadLocal), `TenantInterceptor`, `TenantAwareRepository` base |
| `common.email` | `EmailService` interface, template rendering, provider abstraction |
| `common.audit` | Audit logging annotations + listeners |
| `common.exception` | Global exception handler, error DTOs |
| `common.config` | CORS, Jackson, MongoDB, Redis config |

### 5.2 `admin/` — Admin Console APIs

| Feature | Endpoints |
|---------|----------|
| Tenant Management | `POST/GET/PUT/DELETE /admin/api/tenants` |
| User Management | `POST/GET/PUT/DELETE /admin/api/tenants/{tenantId}/users` |
| Role Management | `POST/GET/PUT/DELETE /admin/api/roles` |
| API Key Management | `POST/GET/PUT/DELETE /admin/api/api-keys` |
| System Dashboard | `GET /admin/api/dashboard/stats` |
| Email Templates | `GET/PUT /admin/api/email-templates` |

### 5.3 `tenant/` — Tenant-Scoped Platform APIs

All requests are automatically scoped to the authenticated user's **active tenant**.

| Feature | Endpoints |
|---------|----------|
| Dashboard | `GET /api/dashboard` |
| Profile | `GET/PUT /api/profile` |
| (Future features) | Extensible per-tenant business logic |

---

## 6. Key Design Decisions to Finalize

These are documented in separate files and need your input:

| Decision | Document |
|----------|----------|
| Multi-tenancy strategy (shared DB vs DB-per-tenant) | [02-multitenancy-design.md](./02-multitenancy-design.md) |
| Users belonging to multiple tenants | [02-multitenancy-design.md](./02-multitenancy-design.md) |
| Authentication & authorization approach | [03-authentication-design.md](./03-authentication-design.md) |
| Email service provider & integration | [04-email-service-design.md](./04-email-service-design.md) |
| API key management & external integration | [05-api-integration-design.md](./05-api-integration-design.md) |
