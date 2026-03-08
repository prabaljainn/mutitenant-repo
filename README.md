# Multitenant Platform

A **multitenant SaaS platform** built with **Spring Boot** and **MongoDB** — designed for managing multiple tenants, users, roles, and tenant-scoped business logic from a single codebase.

## Repository Structure

```
mutitenant-repo/
├── deployment/          # Infrastructure (Docker, MongoDB, Redis, etc.)
├── platform/            # Spring Boot application (code lives here)
└── docs/                # All official documentation
    ├── architecture/    # Architecture docs & design decisions
    ├── features/        # Feature roadmap & backlog
    ├── api/             # API contracts
    └── decisions/       # Architecture Decision Records
```

> **Key principle:** `deployment/` and `platform/` are completely separate. Infrastructure configs and application code don't mix.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Spring Boot 3.x (Java 21) |
| Database | MongoDB 7.x |
| Cache | Redis 7.x |
| Auth | Spring Security + JWT |
| Email | Spring Mail + Thymeleaf |
| Build | Maven |
| Proxy | Traefik |
| Containers | Docker + Docker Compose |

## Quick Start (Infrastructure)

```bash
# 1. Copy environment config
cp deployment/.env.example deployment/.env

# 2. Start MongoDB, Redis, Mailhog
cd deployment
docker compose up -d

# 3. Verify services
docker compose ps
```

| Service | Port | URL |
|---------|------|-----|
| Traefik | 80/443 | `http://localhost` |
| Traefik Dashboard | 8080 | `http://localhost:8080` |
| MongoDB | 27017 | `mongodb://localhost:27017` |
| Redis | 6379 | `redis://localhost:6379` |
| Mailhog SMTP | 1025 | — |
| Mailhog UI | 8025 | http://localhost:8025 |

## Documentation

| Document | Description |
|----------|-------------|
| [System Overview](docs/architecture/01-system-overview.md) | High-level architecture, tech stack, module breakdown |
| [Multi-Tenancy Design](docs/architecture/02-multitenancy-design.md) | Tenancy strategy, data model, user-across-tenants |
| [Authentication Design](docs/architecture/03-authentication-design.md) | JWT auth, RBAC, password reset, security measures |
| [Email Service Design](docs/architecture/04-email-service-design.md) | Provider comparison, templates, async architecture |
| [API Integration Design](docs/architecture/05-api-integration-design.md) | API Key management, security, rate limiting |
| [Feature Roadmap](docs/features/roadmap.md) | Phased feature backlog with priorities |

## Linear Project

Issues tracked at: [GCS-POC on Linear](https://linear.app/norl/team/GCS/all)