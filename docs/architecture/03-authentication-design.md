# Authentication & Authorization Design

## 1. Overview

The authentication system must support:
- Email/password login
- Multi-tenant context switching
- Role-based access control (RBAC) at platform and tenant levels
- Secure password reset via email
- Session/token management
- (Future-ready) OAuth2 social login, MFA

---

## 2. Technology Choices

| Component | Technology | Rationale |
|-----------|-----------|-----------|
| **Auth Framework** | Spring Security 6.x | Native integration, battle-tested, filters-based architecture |
| **Token Format** | JWT (access + refresh tokens) | Stateless API auth; works across microservices if we scale out |
| **Token Store** | Redis (for refresh tokens + blacklist) | Fast invalidation; survives app restarts |
| **Password Hashing** | BCrypt (via Spring Security) | Industry standard, adaptive cost factor |
| **API Security** | Method-level `@PreAuthorize` + custom tenant filter | Fine-grained RBAC |
| **API Docs** | SpringDoc OpenAPI with SecurityScheme | JWT bearer auth in Swagger UI |

---

## 3. Authentication Flow

### 3.1 Login Flow

```
Client                         Server                         MongoDB        Redis
  │                               │                              │              │
  │  POST /api/auth/login         │                              │              │
  │  { email, password }          │                              │              │
  │──────────────────────────────>│                              │              │
  │                               │  Find user by email          │              │
  │                               │─────────────────────────────>│              │
  │                               │  user document               │              │
  │                               │<─────────────────────────────│              │
  │                               │                              │              │
  │                               │  Verify BCrypt password      │              │
  │                               │  Load tenant memberships     │              │
  │                               │─────────────────────────────>│              │
  │                               │  memberships[]               │              │
  │                               │<─────────────────────────────│              │
  │                               │                              │              │
  │                               │  Generate JWT (access token) │              │
  │                               │  Generate refresh token      │              │
  │                               │  Store refresh token ────────┼─────────────>│
  │                               │                              │              │
  │  { accessToken, refreshToken, │                              │              │
  │    tenantMemberships[] }      │                              │              │
  │<──────────────────────────────│                              │              │
```

### 3.2 Token Structure

**Access Token (JWT, short-lived: 15 min)**
```json
{
  "sub": "user_id",
  "email": "prabal@example.com",
  "systemRole": "USER",
  "activeTenantId": "tenant_abc",
  "tenantRoles": ["ADMIN"],
  "iat": 1709942400,
  "exp": 1709943300
}
```

**Refresh Token (opaque, stored in Redis, long-lived: 7 days)**
```json
{
  "key": "rt:user_id:random_uuid",
  "value": {
    "userId": "user_id",
    "deviceInfo": "...",
    "createdAt": "...",
    "expiresAt": "..."
  }
}
```

### 3.3 Token Refresh Flow

```
POST /api/auth/refresh
{ refreshToken: "..." }

→ Validate refresh token exists in Redis
→ Check not expired / blacklisted
→ Issue new access token + rotate refresh token
→ Delete old refresh token from Redis
→ Return new token pair
```

### 3.4 Tenant Switch Flow

```
POST /api/auth/switch-tenant
{ tenantId: "tenant_xyz" }
Authorization: Bearer <current_access_token>

→ Verify user has membership in target tenant
→ Issue new access token with updated activeTenantId + tenantRoles
→ Return new access token (same refresh token is valid)
```

---

## 4. Password Reset Flow

```
1. POST /api/auth/forgot-password
   { email: "prabal@example.com" }
   → Generate a secure random token (stored in Redis, TTL: 1 hour)
   → Send email with reset link: domain.com/reset-password?token=xxx

2. POST /api/auth/reset-password
   { token: "xxx", newPassword: "..." }
   → Validate token from Redis
   → Update password hash in MongoDB
   → Invalidate all existing refresh tokens for the user
   → Delete reset token from Redis
   → Send confirmation email
```

---

## 5. Authorization Model

### 5.1 Spring Security Filter Chain

```java
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) {
        http
            .csrf(csrf -> csrf.disable())  // JWT-based, not session-based
            .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Public endpoints
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/actuator/health").permitAll()

                // Admin endpoints — SUPER_ADMIN only
                .requestMatchers("/admin/api/**").hasRole("SUPER_ADMIN")

                // Platform endpoints — authenticated + tenant context required
                .requestMatchers("/api/**").authenticated()
            )
            .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(jwtAuthFilter, ApiKeyAuthFilter.class)
            .addFilterAfter(tenantContextFilter, JwtAuthFilter.class);

        return http.build();
    }
}
```

### 5.2 Method-Level Authorization Examples

```java
// Only tenant ADMINs can invite users
@PreAuthorize("hasAnyTenantRole('TENANT_OWNER', 'ADMIN')")
public UserDTO inviteUserToTenant(String email, List<String> roles) { ... }

// Only SUPER_ADMIN can create tenants
@PreAuthorize("hasRole('SUPER_ADMIN')")
public TenantDTO createTenant(CreateTenantRequest req) { ... }

// Any authenticated user with tenant context
@PreAuthorize("hasTenantAccess()")
public DashboardDTO getDashboard() { ... }
```

---

## 6. Security Measures

| Measure | Implementation |
|---------|---------------|
| **Brute force protection** | Rate limiting on `/api/auth/login` (e.g., 5 attempts / 15 min per IP via Redis) |
| **Token blacklisting** | On logout / password change, add access token JTI to Redis blacklist |
| **Refresh token rotation** | Each refresh issues a new refresh token; old one is invalidated |
| **Password policy** | Min 8 chars, must contain upper + lower + digit. Configurable per tenant |
| **CORS** | Strict origins: only `admin.domain.com` and `domain.com` |
| **Audit trail** | Log all auth events: login, logout, password change, tenant switch |
| **Secure headers** | HSTS, X-Content-Type-Options, X-Frame-Options via Spring Security |

---

## 7. Auth API Summary

| Endpoint | Method | Auth Required | Description |
|----------|--------|--------------|-------------|
| `/api/auth/register` | POST | No | Register new user (if self-registration enabled) |
| `/api/auth/login` | POST | No | Login with email + password |
| `/api/auth/refresh` | POST | No (refresh token in body) | Refresh access token |
| `/api/auth/logout` | POST | Yes | Invalidate refresh token + blacklist access token |
| `/api/auth/forgot-password` | POST | No | Request password reset email |
| `/api/auth/reset-password` | POST | No (reset token in body) | Reset password with token |
| `/api/auth/switch-tenant` | POST | Yes | Switch active tenant context |
| `/api/auth/me` | GET | Yes | Get current user profile + memberships |

---

## 8. Libraries & Dependencies

```xml
<!-- pom.xml -->
<dependencies>
    <!-- Spring Security -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security</artifactId>
    </dependency>

    <!-- JWT -->
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-api</artifactId>
        <version>0.12.6</version>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-impl</artifactId>
        <version>0.12.6</version>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-jackson</artifactId>
        <version>0.12.6</version>
        <scope>runtime</scope>
    </dependency>

    <!-- Redis -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-redis</artifactId>
    </dependency>

    <!-- Validation -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>

    <!-- (Future) OAuth2 social login -->
    <!--
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-oauth2-client</artifactId>
    </dependency>
    -->
</dependencies>
```

---

## 9. Questions for You

> [!IMPORTANT]
> **Q4:** Do you want **self-registration** (users can sign up on their own and then be assigned to tenants), or is user creation **admin/invitation-only**?

> [!IMPORTANT]
> **Q5:** Do you want **OAuth2 social login** (Google, GitHub, etc.) in the initial version, or is email/password sufficient to start with?

> [!IMPORTANT]
> **Q6:** Should **MFA (Multi-Factor Authentication)** be in the initial scope, or is it a later enhancement? (The architecture is designed to support it either way.)
