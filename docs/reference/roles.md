# Roles & hierarchy

Quick reference for who can do what. There is **no `SUPER_ADMIN` above `OPERATOR_ADMIN`** — `OPERATOR_ADMIN` is the top of the chain and what the dev bootstrap creates.

---

## Two top-level user kinds

```
UserKind (in JWT as "kind")
├── OPERATOR        — Orochiverse staff. Cross-tenant. No tenantId on the user record.
└── TENANT_USER     — Belongs to exactly one customer tenant. Has tenantId.
```

A user is exclusively one or the other (enforced in `User`'s constructor).

---

## Operator roles (only for `OPERATOR`)

```
OperatorRole (in JWT as "opRole")
├── OPERATOR_ADMIN     — "SUPER_ADMIN" in informal speak. Full control.
└── OPERATOR_SUPPORT   — Read-mostly + bounded support actions.
```

The role is **uniform across all assigned tenants** — an operator is the same role in every tenant they have an `OperatorAssignment` for. No per-tenant role variation by design.

---

## Tenant roles (only for `TENANT_USER`)

```
TenantRole (in JWT as "tRole") — descending privilege
├── TENANT_OWNER  — First admin of the tenant. Can transfer ownership. Full control inside tenant.
├── ADMIN         — Manage users, settings, content. Cannot transfer ownership.
├── EDITOR        — Read + write content. No user management.
└── VIEWER        — Read-only.
```

Tenant users are **scoped to one tenant for the life of their account** — their `tenantId` is set at invite time and never changes.

---

## OperatorAssignment — separate from role

A row in `iam_db.operator_assignments`: `(operatorUserId, tenantId, assignedBy, assignedAt)` with a unique compound index on `(operatorUserId, tenantId)`. This is **which tenants** an operator may act in, not **what** they can do — the latter is their `OperatorRole`.

So an `OPERATOR_SUPPORT` assigned to tenants A, B, C can do support in those three tenants but not in D.

---

## Hierarchy diagram

```
                     ┌─────────────────────────┐
                     │       UserKind          │
                     └────────────┬────────────┘
                                  │
                ┌─────────────────┴─────────────────┐
                ▼                                   ▼
         ┌────────────┐                      ┌─────────────┐
         │  OPERATOR  │                      │ TENANT_USER │
         │ (Orochi)   │                      │ (customer)  │
         └─────┬──────┘                      └──────┬──────┘
               │                                    │
       ┌───────┴───────┐                ┌───────────┼───────────┬──────────┐
       ▼               ▼                ▼           ▼           ▼          ▼
   OPERATOR_ADMIN  OPERATOR_SUPPORT  TENANT_OWNER ADMIN     EDITOR      VIEWER
   (everything)    (read+support)    (full+xfer)  (full)    (content)  (read-only)
       │
       ├──── via OperatorAssignment(s) ────►   acts inside tenants A, B, C…
```

---

## How the JWT carries it

Per spec §5.1 (also in `AccessTokenClaims`):

```json
{
  "sub":   "<userId>",
  "kind":  "OPERATOR" | "TENANT_USER",
  "opRole": "OPERATOR_ADMIN" | "OPERATOR_SUPPORT" | null,
  "tid":   "<activeTenantId>" | null,
  "tRole": "TENANT_OWNER" | "ADMIN" | "EDITOR" | "VIEWER" | null,
  "tv":    0
}
```

For an operator who hasn't switched tenants: `tid = null`, `tRole = null`. After `POST /api/auth/switch-tenant`: `tid = <tenant>`, `tRole = null` (their authority comes from `opRole`, not a tenant role).

---

## Spring Security authorities (`AuthorityResolver`)

Each principal gets **two** `ROLE_*` authorities:

| User | Authorities |
|---|---|
| Operator-admin | `ROLE_OPERATOR`, `ROLE_OPERATOR_ADMIN` |
| Operator-support | `ROLE_OPERATOR`, `ROLE_OPERATOR_SUPPORT` |
| Tenant owner | `ROLE_TENANT_USER`, `ROLE_TENANT_OWNER` |
| Tenant admin | `ROLE_TENANT_USER`, `ROLE_ADMIN` |
| Tenant editor | `ROLE_TENANT_USER`, `ROLE_EDITOR` |
| Tenant viewer | `ROLE_TENANT_USER`, `ROLE_VIEWER` |

So `@PreAuthorize("hasRole('OPERATOR')")` matches both operator subkinds; `hasRole('OPERATOR_ADMIN')` matches only the admin variant. Same pattern for tenant side.

---

## Endpoint matrix (M1)

### Auth (`/api/auth/*`)

| Endpoint | Method | Required role |
|---|---|---|
| `/login` | POST | (public) — rate-limited per `(email, ip)`, 5/15min |
| `/refresh` | POST | (public) — sliding refresh-token window |
| `/forgot-password` | POST | (public) |
| `/reset-password` | POST | (public) — consumes single-use token, bumps `tv` |
| `/accept-invite` | POST | (public) — consumes single-use token |
| `/logout` | POST | any authenticated |
| `/me` | GET | any authenticated |
| `/switch-tenant` | POST | `OPERATOR` (any subkind) |

### Operator admin (`/admin/api/*`)

| Endpoint | Method | Required role |
|---|---|---|
| `/stats/overview` | GET | `OPERATOR` |
| `/tenants` | GET | `OPERATOR` |
| `/tenants` | POST | `OPERATOR_ADMIN` |
| `/tenants/{id}` | GET | `OPERATOR` |
| `/tenants/{id}` | PUT / DELETE | `OPERATOR_ADMIN` |
| `/tenants/{tenantId}/users` | GET | `OPERATOR` |
| `/tenants/{tenantId}/users` | POST | `OPERATOR_ADMIN` — invites a tenant user; bumps `tv` on suspend/role-change/delete |
| `/tenants/{tenantId}/users/{userId}` | GET | `OPERATOR` |
| `/tenants/{tenantId}/users/{userId}` | PUT / DELETE | `OPERATOR_ADMIN` |
| `/tenants/{tenantId}/settings` | GET | `OPERATOR` — list all kinds |
| `/tenants/{tenantId}/settings/{kind}` | GET | `OPERATOR` |
| `/tenants/{tenantId}/settings/{kind}` | PUT / DELETE | `OPERATOR_ADMIN` |
| `/tenants/{tenantId}/settings/{kind}/test` | POST | `OPERATOR_ADMIN` — dry-run via `ConnectionTester` (SSRF-guarded) |
| `/operators` | GET | `OPERATOR` |
| `/operators` | POST | `OPERATOR_ADMIN` |
| `/operators/{id}` | GET | `OPERATOR` |
| `/operators/{id}` | PUT / DELETE | `OPERATOR_ADMIN` |
| `/operators/{operatorId}/assignments` | GET | `OPERATOR` |
| `/operators/{operatorId}/assignments` | POST | `OPERATOR_ADMIN` |
| `/operators/{operatorId}/assignments/{tenantId}` | DELETE | `OPERATOR_ADMIN` |
| `/audit` | GET | `OPERATOR` |

### Tenant self-service (`/api/tenant/*`)

| Endpoint | Method | Required role |
|---|---|---|
| `/me` | GET | any `TENANT_USER` |
| `/users` | GET | any `TENANT_USER` |
| `/users` | POST | `TENANT_OWNER` or `ADMIN` |
| `/users/{id}` | GET | any `TENANT_USER` |
| `/users/{id}` | PUT / DELETE | `TENANT_OWNER` or `ADMIN` — bumps `tv` on suspend/role-change/delete |
| `/settings` | GET | `TENANT_OWNER` or `ADMIN` — read-only view of own tenant's settings |
| `/settings/{kind}` | GET | `TENANT_OWNER` or `ADMIN` |

### Infra surface

| Endpoint | Method | Required role |
|---|---|---|
| `/.well-known/jwks.json`, `/actuator/health/**`, `/actuator/info`, `/v3/api-docs/**`, `/swagger-ui/**` | GET | (public) |
| `/actuator/prometheus`, `/actuator/metrics/**` | GET | any authenticated — **no longer public**; gate further by network policy |

---

## What's NOT in the model

- **No `SUPER_ADMIN` above `OPERATOR_ADMIN`.** `OPERATOR_ADMIN` is the top of the chain — your effective super-admin.
- **No per-tenant operator role variation.** Operators have one global role (`OPERATOR_ADMIN` / `_SUPPORT`); they're either assigned to a tenant or not.
- **No tenant-role hierarchy in code** — `TENANT_OWNER` doesn't auto-grant `ADMIN` authorities. If you want hierarchical inheritance (`OWNER → ADMIN → EDITOR → VIEWER`), wire a Spring `RoleHierarchy` bean. Today, `@PreAuthorize("hasRole('EDITOR')")` rejects an `OWNER`. Phase 1.8 controllers explicitly declare each acceptable role with `hasAnyRole(...)`.
- **No groups, scopes, or fine-grained permissions** — flat `kind + role` model. Adequate for M1; revisit if SSO/SAML lands.
