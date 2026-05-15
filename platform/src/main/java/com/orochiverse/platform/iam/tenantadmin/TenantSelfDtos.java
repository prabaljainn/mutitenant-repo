package com.orochiverse.platform.iam.tenantadmin;

import java.time.Instant;
import java.util.Map;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.orochiverse.platform.common.security.principals.TenantRole;
import com.orochiverse.platform.iam.tenants.Tenant;
import com.orochiverse.platform.iam.users.User;
import com.orochiverse.platform.iam.users.UserStatus;

/**
 * Request and response DTOs for the {@code /api/tenant/*} self-service
 * surface. Tenant users only ever see other users in their own tenant —
 * the service layer enforces that by filtering on
 * {@link com.orochiverse.platform.common.tenant.TenantContext#requireCurrent()}.
 *
 * <p>Operators don't share this surface — they go through {@code /admin/api/*}
 * even when switched into a tenant.
 */
public final class TenantSelfDtos {

    private TenantSelfDtos() {}

    /**
     * Invite a tenant user. {@code role} is either {@code ADMIN} or
     * {@code MEMBER}. The very first {@code ADMIN} invited to a fresh
     * tenant is auto-promoted to the tenant's owner.
     */
    public record InviteTenantUserRequest(
            @Email @NotBlank String email,
            @NotBlank String firstName,
            @NotBlank String lastName,
            @NotNull TenantRole role) {}

    /**
     * Partial update — null fields are left alone. {@code status} cannot
     * be set to {@link UserStatus#DELETED} (use {@code DELETE /api/tenant/users/{id}}).
     */
    public record UpdateTenantUserRequest(
            String firstName,
            String lastName,
            TenantRole role,
            UserStatus status) {}

    public record TenantUserResponse(
            String id,
            String email,
            String firstName,
            String lastName,
            UserStatus status,
            TenantRole role,
            Instant lastLoginAt,
            Instant createdAt,
            Instant updatedAt) {

        public static TenantUserResponse from(User u) {
            return new TenantUserResponse(u.id(), u.email(), u.firstName(), u.lastName(),
                    u.status(), u.tenantRole(), u.lastLoginAt(), u.createdAt(), u.updatedAt());
        }
    }

    /**
     * {@code GET /api/tenant/me} response: the current user combined with
     * a read-only view of their tenant. Saves the SPA two round-trips on
     * boot.
     */
    public record TenantMeResponse(MeUser user, MeTenant tenant) {

        public record MeUser(
                String id,
                String email,
                String firstName,
                String lastName,
                TenantRole role,
                UserStatus status,
                Instant lastLoginAt) {

            public static MeUser from(User u) {
                return new MeUser(u.id(), u.email(), u.firstName(), u.lastName(),
                        u.tenantRole(), u.status(), u.lastLoginAt());
            }
        }

        public record MeTenant(
                String id,
                String name,
                String ownerUserId,
                Map<String, Object> settings) {

            public static MeTenant from(Tenant t) {
                return new MeTenant(t.id(), t.name(), t.ownerUserId(), t.settings());
            }
        }
    }
}
