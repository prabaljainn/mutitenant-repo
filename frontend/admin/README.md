# CloudGCS Admin Console (Next.js)

Multi-tenant admin frontend for the Orochiverse platform. Five screens:

1. **Login** — super-admin sign-in (calls real `POST /api/auth/login`).
2. **Overview** — stats + tenants table + recent activity.
3. **Tenants** — list, search, create.
4. **Tenant detail** — Overview / Members / Settings tabs, inline rename, invite flow.
5. **Global Settings** — per-tenant MQTT + DJI configuration.

## Stack

| Layer | Choice |
|---|---|
| Framework | Next.js 15 (App Router) |
| Language | TypeScript strict |
| Styling | Plain CSS variables (`globals.css` ships the design tokens) |
| State | TanStack Query v5 for server state; React Context for auth/theme/toast |
| Auth | JWT (RS256) issued by Spring backend; access token in memory, refresh via `POST /api/auth/refresh` |
| Icons | Inline SVG path strings (`src/components/icons/icons.ts`) — no icon library |
| Fonts | `Space Grotesk` (UI) + `JetBrains Mono` (mono cells) via `next/font/google` |

## Local dev

```
npm install
cp .env.example .env.local
# point PLATFORM_API_BASE at a running Spring backend, or leave NEXT_PUBLIC_USE_MOCKS=true
npm run dev      # http://localhost:3001
```

## Backend contract

The frontend expects the Spring platform to expose:

- `POST /api/auth/login` (implemented)
- `POST /api/auth/refresh` (implemented)
- `POST /api/auth/logout` (implemented)
- `GET /api/admin/tenants` (not yet — UI shows a typed `NotImplemented` empty state)
- `POST /api/admin/tenants` (not yet)
- `GET /api/admin/tenants/{id}/users` (not yet)
- `GET /api/admin/tenants/{id}/settings/{mqtt,dji}` (not yet)
- `GET /api/admin/dashboard/{stats,recent-activity}` (not yet)

Endpoints that return 404/501 from the backend surface a clear inline message in the UI so backend gaps are visible. The fetch wrapper auto-rotates the access token on 401.

## Build + deploy

```
docker build -t orochiverse/admin-frontend .
docker run --rm -p 3001:3001 -e PLATFORM_API_BASE=http://platform:8080 orochiverse/admin-frontend
```

The Dockerfile uses Next's standalone output — final image is ~150 MB.
