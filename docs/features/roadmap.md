# Feature Roadmap & Backlog

## 🏁 Phase 0: Foundation (Current)
> Setting the base — no code, documentation and decisions only.

| # | Feature | Status | Priority | Notes |
|---|---------|--------|----------|-------|
| F-001 | Architecture documentation | 🟡 In Progress | P0 | System overview, multi-tenancy, auth, email |
| F-002 | Deployment infrastructure (Docker Compose) | 🟡 In Progress | P0 | MongoDB, Redis, Mailhog |
| F-003 | Repository structure setup | 🟡 In Progress | P0 | deployment/ + platform/ separation |
| F-004 | Technology decisions finalized | 🔴 Blocked | P0 | Pending review of architecture docs |

---

## 🏗️ Phase 1: Core Platform (Next)
> Build the skeleton — Spring Boot project, auth, basic admin.

| # | Feature | Status | Priority | Notes |
|---|---------|--------|----------|-------|
| F-010 | Spring Boot project initialization | ⚪ Not Started | P0 | Gradle multi-module, Java 21 |
| F-011 | MongoDB connection + base config | ⚪ Not Started | P0 | Spring Data MongoDB |
| F-012 | Tenant data model + CRUD | ⚪ Not Started | P0 | Create/Read/Update/Deactivate tenants |
| F-013 | User data model + CRUD | ⚪ Not Started | P0 | Users with multi-tenant memberships |
| F-014 | Authentication (login/logout/refresh) | ⚪ Not Started | P0 | JWT + refresh tokens in Redis |
| F-015 | Password reset flow | ⚪ Not Started | P0 | Email-based reset with secure tokens |
| F-016 | Tenant context interceptor | ⚪ Not Started | P0 | Auto-filter all queries by tenantId |
| F-017 | Role-based authorization (RBAC) | ⚪ Not Started | P0 | Platform roles + tenant roles |
| F-018 | Email service (password reset emails) | ⚪ Not Started | P1 | Thymeleaf templates + SMTP/SendGrid |

---

## 🎨 Phase 2: Admin Dashboard API
> Super-admin functionality — manage tenants, users, system.

| # | Feature | Status | Priority | Notes |
|---|---------|--------|----------|-------|
| F-020 | Admin - List/search all tenants | ⚪ Not Started | P0 | With pagination, search, filters |
| F-021 | Admin - Create new tenant | ⚪ Not Started | P0 | With initial owner user setup |
| F-022 | Admin - View/edit tenant details | ⚪ Not Started | P0 | Name, slug, status, plan |
| F-023 | Admin - Suspend/activate tenant | ⚪ Not Started | P1 | Soft-disable all access |
| F-024 | Admin - List users across tenants | ⚪ Not Started | P0 | Cross-tenant user view |
| F-025 | Admin - User management in tenant | ⚪ Not Started | P0 | Add/remove/change roles |
| F-026 | Admin - Role management | ⚪ Not Started | P1 | Define custom roles if needed |
| F-027 | Admin - System dashboard (stats) | ⚪ Not Started | P2 | Total tenants, users, activity |
| F-028 | Admin - Email template management | ⚪ Not Started | P2 | View/edit email templates |

---

## 👤 Phase 3: Tenant Platform API
> End-user experience — tenant-scoped features.

| # | Feature | Status | Priority | Notes |
|---|---------|--------|----------|-------|
| F-030 | Tenant dashboard | ⚪ Not Started | P1 | Tenant-specific overview |
| F-031 | User profile management | ⚪ Not Started | P1 | View/edit own profile |
| F-032 | Tenant switching | ⚪ Not Started | P1 | Switch between tenants (multi-tenant users) |
| F-033 | User invitation flow | ⚪ Not Started | P1 | Invite by email, accept invitation |

---

## 🔐 Phase 4: Advanced Security
> Hardening the platform.

| # | Feature | Status | Priority | Notes |
|---|---------|--------|----------|-------|
| F-040 | Rate limiting (login, API) | ⚪ Not Started | P1 | Redis-based |
| F-041 | Audit trail logging | ⚪ Not Started | P1 | Who did what, when |
| F-042 | MFA (TOTP) | ⚪ Not Started | P2 | Optional per-user |
| F-043 | OAuth2 social login | ⚪ Not Started | P2 | Google, GitHub |
| F-044 | API key management | ⚪ Not Started | P3 | For programmatic access |

---

## 🎯 Phase 5: Advanced Features (Future)
> Based on business requirements.

| # | Feature | Status | Priority | Notes |
|---|---------|--------|----------|-------|
| F-050 | Tenant billing / plan management | ⚪ Not Started | P2 | Free/Pro/Enterprise tiers |
| F-051 | Tenant-branded emails | ⚪ Not Started | P3 | Custom logos/colors in emails |
| F-052 | Webhooks | ⚪ Not Started | P3 | Event notifications to tenants |
| F-053 | API rate limiting per tenant/plan | ⚪ Not Started | P3 | Plan-based quotas |
| F-054 | Admin UI (frontend) | ⚪ Not Started | P2 | React/Next.js admin dashboard |
| F-055 | Tenant Platform UI (frontend) | ⚪ Not Started | P2 | React/Next.js tenant app |

---

## Priority Legend

| Symbol | Meaning |
|--------|---------|
| P0 | Must have — blocks everything else |
| P1 | Should have — expected in first usable version |
| P2 | Nice to have — second iteration |
| P3 | Future — planned but not urgent |
