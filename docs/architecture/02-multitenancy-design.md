# Multi-Tenancy Design

## 1. Problem Statement

We need a multi-tenancy model that supports:
- **Tenant isolation** — each tenant sees only its own data
- **A single user belonging to multiple tenants** — users can switch context
- **Super-admin visibility** across all tenants
- **Easy onboarding** of new tenants without database-level changes

---

## 2. Multi-Tenancy Strategies Compared

| Strategy | Description | Pros | Cons |
|----------|-------------|------|------|
| **A. Shared Database, Shared Collection** (discriminator field) | All tenants share the same MongoDB collections. Every document has a `tenantId` field. | Simplest to implement; easy cross-tenant queries for admin; low operational cost | Requires discipline with filters; noisy-neighbor risk without query safeguards |
| **B. Shared Database, Separate Collections** | Each tenant gets its own set of collections (`tenant1_users`, `tenant2_users`...) | Better logical isolation | Explosion of collections; harder to query across tenants; Spring Data doesn't natively support dynamic collection names well |
| **C. Database Per Tenant** | Each tenant gets its own MongoDB database | Strongest isolation; easy backup/restore per tenant | Connection pool management; complex admin queries; harder to manage at scale |

### ✅ Recommendation: **Strategy A — Shared Database with Discriminator `tenantId`**

**Why:**
1. MongoDB's flexible schema + Spring Data MongoDB makes discriminator-based tenancy straightforward
2. Admin cross-tenant queries (e.g., "list all users across all tenants") are trivial
3. Indexes on `tenantId` + other fields give excellent query performance
4. Operational simplicity — single database to backup, monitor, scale
5. For the scale we're targeting, noisy-neighbor is easily mitigated with proper indexing

**Safeguards we'll implement:**
- `TenantInterceptor` that auto-injects `tenantId` filter on every query
- `@TenantScoped` annotation to enforce tenancy on repository methods
- Compound indexes: `{ tenantId: 1, ... }` on all tenant-scoped collections
- Admin-only queries explicitly bypass the tenant filter

---

## 3. Users Across Multiple Tenants

### The Question
> *"A single user can belong to 3 tenants also, so should we have this feature?"*

### ✅ Recommendation: **YES — Implement multi-tenant user membership from Day 1**

Retrofitting this later is extremely painful. Here's the proposed data model:

### 3.1 Data Model

```
┌─────────────────────────────────────────────────────┐
│                     users                            │
├─────────────────────────────────────────────────────┤
│  _id: ObjectId                                       │
│  email: "prabal@example.com"        (unique, global) │
│  passwordHash: "..."                                 │
│  firstName: "Prabal"                                 │
│  lastName: "Jain"                                    │
│  status: "ACTIVE"                                    │
│  systemRole: "SUPER_ADMIN" | "USER"  (platform-wide) │
│  mfaEnabled: false                                   │
│  createdAt / updatedAt                               │
└─────────────────────────────────────────────────────┘
          │
          │ 1:N
          ▼
┌─────────────────────────────────────────────────────┐
│                  tenant_memberships                   │
├─────────────────────────────────────────────────────┤
│  _id: ObjectId                                       │
│  userId: ObjectId (ref → users)                      │
│  tenantId: ObjectId (ref → tenants)                  │
│  roles: ["ADMIN", "EDITOR"]   (tenant-scoped roles)  │
│  status: "ACTIVE" | "INVITED" | "SUSPENDED"          │
│  invitedBy: ObjectId                                 │
│  joinedAt: Date                                      │
│  lastAccessedAt: Date                                │
└─────────────────────────────────────────────────────┘
          │
          │ N:1
          ▼
┌─────────────────────────────────────────────────────┐
│                     tenants                          │
├─────────────────────────────────────────────────────┤
│  _id: ObjectId                                       │
│  name: "Acme Corp"                                   │
│  slug: "acme-corp"                                   │
│  domain: "acme.domain.com" (optional, for subdomain) │
│  status: "ACTIVE" | "SUSPENDED" | "TRIAL"            │
│  plan: "FREE" | "PRO" | "ENTERPRISE"                 │
│  settings: { ... }              (tenant-level config) │
│  createdAt / updatedAt                               │
└─────────────────────────────────────────────────────┘
```

### 3.2 How Tenant Switching Works

```
1. User logs in → receives JWT containing:
   {
     "sub": "userId",
     "email": "prabal@example.com",
     "systemRole": "USER",
     "activeTenantId": "tenant_abc",       ← currently active tenant
     "tenantMemberships": [                 ← all tenant memberships
       { "tenantId": "tenant_abc", "roles": ["ADMIN"] },
       { "tenantId": "tenant_xyz", "roles": ["VIEWER"] }
     ]
   }

2. User hits: POST /api/auth/switch-tenant
   Body: { "tenantId": "tenant_xyz" }
   → New JWT issued with updated activeTenantId

3. TenantInterceptor reads activeTenantId from JWT
   → Sets TenantContext.setCurrentTenantId("tenant_xyz")
   → All repository queries auto-filter by tenantId
```

### 3.3 Role Hierarchy

```
Platform Level (systemRole):
├── SUPER_ADMIN    → Full access to admin console, all tenants
└── USER           → Regular user, access depends on tenant memberships

Tenant Level (roles in tenant_memberships):
├── TENANT_OWNER   → Full control of the tenant
├── ADMIN          → Manage users/roles within the tenant
├── EDITOR         → Read + write within the tenant
└── VIEWER         → Read-only within the tenant
```

---

## 4. Collection Index Strategy

```javascript
// users collection
db.users.createIndex({ "email": 1 }, { unique: true })
db.users.createIndex({ "systemRole": 1 })

// tenant_memberships collection
db.tenant_memberships.createIndex({ "userId": 1, "tenantId": 1 }, { unique: true })
db.tenant_memberships.createIndex({ "tenantId": 1, "status": 1 })
db.tenant_memberships.createIndex({ "userId": 1, "status": 1 })

// tenants collection
db.tenants.createIndex({ "slug": 1 }, { unique: true })
db.tenants.createIndex({ "domain": 1 }, { sparse: true, unique: true })

// All tenant-scoped business collections
db.<collection>.createIndex({ "tenantId": 1, ... })
```

---

## 5. Questions for You

> [!IMPORTANT]
> **Q1:** Are you okay with **Strategy A (shared database with `tenantId` discriminator)**? If you anticipate very strict data isolation requirements (e.g., regulatory/compliance), we should discuss Strategy C.

> [!IMPORTANT]
> **Q2:** For the multi-tenant user membership model above — should we support **custom roles per tenant** (each tenant can define their own roles), or are the **fixed roles** (OWNER/ADMIN/EDITOR/VIEWER) sufficient?

> [!IMPORTANT]
> **Q3:** Do you need **subdomain-based tenant resolution** (`acme.domain.com`) in addition to auth-context-based resolution? This impacts DNS setup and the reverse proxy config.
