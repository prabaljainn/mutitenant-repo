package com.orochiverse.platform.iam.admin.operators;

import java.time.Instant;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.orochiverse.platform.common.security.principals.OperatorRole;
import com.orochiverse.platform.iam.users.User;
import com.orochiverse.platform.iam.users.UserStatus;

/**
 * Request and response DTOs for {@code /admin/api/operators}. Operators
 * are created in {@link UserStatus#INVITED} via this surface — no password
 * is set; the invite-acceptance flow (Phase 1.9 email + reset) sets one.
 */
public final class OperatorDtos {

    private OperatorDtos() {}

    public record InviteOperatorRequest(
            @Email @NotBlank String email,
            @NotBlank String firstName,
            @NotBlank String lastName,
            @NotNull OperatorRole role) {}

    /** Partial update — null fields are left alone. */
    public record UpdateOperatorRequest(
            String firstName,
            String lastName,
            OperatorRole role,
            UserStatus status) {}

    public record OperatorResponse(
            String id,
            String email,
            String firstName,
            String lastName,
            UserStatus status,
            OperatorRole role,
            Instant lastLoginAt,
            Instant createdAt,
            Instant updatedAt) {

        public static OperatorResponse from(User u) {
            return new OperatorResponse(u.id(), u.email(), u.firstName(), u.lastName(),
                    u.status(), u.operatorRole(), u.lastLoginAt(), u.createdAt(), u.updatedAt());
        }
    }
}
