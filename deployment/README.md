# Deployment Infrastructure

## Services

This Docker Compose file provides the infrastructure services for the multitenant platform.
The platform application itself (`platform/`) is separate.

## Quick Start

```bash
# Start all services
docker compose up -d

# Check status
docker compose ps

# View logs
docker compose logs -f mongodb

# Stop all services
docker compose down

# Stop and remove volumes (CAUTION: deletes data)
docker compose down -v
```

## Services Overview

| Service | Port | Purpose |
|---------|------|---------|
| MongoDB | 27017 | Primary database |
| Redis | 6379 | Cache, sessions, token store |
| Mailhog | 1025 (SMTP), 8025 (Web UI) | Dev email testing |

## Environment Variables

Copy `.env.example` to `.env` and configure:

```bash
cp .env.example .env
```

## Data Persistence

All data is persisted via Docker volumes:
- `mongodb_data` — MongoDB data files
- `redis_data` — Redis persistence

## MongoDB Initialization

On first start, MongoDB runs scripts from `mongodb/init-scripts/`:
- Creates the application database
- Sets up the application user with appropriate permissions
- Creates initial indexes

## Mailhog Web UI

After starting services, view captured emails at: http://localhost:8025
