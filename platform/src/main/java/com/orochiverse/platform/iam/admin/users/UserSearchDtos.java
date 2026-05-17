package com.orochiverse.platform.iam.admin.users;

import java.time.Instant;

import com.orochiverse.platform.common.security.principals.UserKind;
import com.orochiverse.platform.iam.users.User;
import com.orochiverse.platform.iam.users.UserStatus;

/**
 * Response DTOs for {@code /admin/api/users/search}. The cross-tenant
 * search treats operators and tenant users uniformly; {@code kind} +
 * {@code tenantId} let the caller route a click to the right detail page.
 */
public final class UserSearchDtos {

    private UserSearchDtos() {}

    /**
     * One match row. {@code role} collapses {@code operatorRole} and
     * {@code tenantRole} into a single string field so the caller doesn't
     * need to inspect the enum hierarchy. {@code tenantId} is {@code null}
     * for operators.
     */
    public record UserSearchResult(
            String userId,
            String email,
            String firstName,
            String lastName,
            UserKind kind,
            String tenantId,
            String role,
            UserStatus status,
            Instant lastLoginAt) {

        public static UserSearchResult from(User u) {
            String role = switch (u.kind()) {
                case OPERATOR -> u.operatorRole() == null ? null : u.operatorRole().name();
                case TENANT_USER -> u.tenantRole() == null ? null : u.tenantRole().name();
            };
            return new UserSearchResult(
                    u.id(), u.email(), u.firstName(), u.lastName(),
                    u.kind(), u.tenantId(), role, u.status(), u.lastLoginAt());
        }
    }
}
