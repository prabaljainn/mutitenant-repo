package com.orochiverse.platform.iam.users;

import java.time.Instant;
import java.util.Objects;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import com.orochiverse.platform.common.tenant.TenantId;

/**
 * A platform user — either an Orochiverse operator or an end user belonging
 * to exactly one tenant.
 *
 * <p>The two shapes are unified into one record because their persistence
 * model is identical (one collection in {@code iam_db.users}). The
 * {@link UserKind} discriminator decides which fields are populated;
 * invariants are enforced in the canonical constructor.
 *
 * <ul>
 *   <li>For {@link UserKind#OPERATOR}: {@code operatorRole} required;
 *       {@code tenantId} and {@code tenantRole} must be {@code null}.</li>
 *   <li>For {@link UserKind#TENANT_USER}: {@code tenantId} and
 *       {@code tenantRole} required; {@code operatorRole} must be
 *       {@code null}.</li>
 * </ul>
 *
 * Use the factory methods {@link #newOperator} / {@link #newTenantUser} to
 * avoid getting any of this wrong.
 */
@Document(collection = "users")
public record User(
        @Id String id,
        String email,
        String passwordHash,
        String firstName,
        String lastName,
        UserStatus status,
        UserKind kind,
        OperatorRole operatorRole,
        String tenantId,
        TenantRole tenantRole,
        int tokenVersion,
        Instant lastLoginAt,
        Instant createdAt,
        Instant updatedAt) {

    public User {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (email.isBlank()) {
            throw new IllegalArgumentException("email must not be blank");
        }

        switch (kind) {
            case OPERATOR -> {
                if (operatorRole == null) {
                    throw new IllegalArgumentException("OPERATOR users must have an operatorRole");
                }
                if (tenantId != null || tenantRole != null) {
                    throw new IllegalArgumentException(
                            "OPERATOR users must not have tenantId or tenantRole");
                }
            }
            case TENANT_USER -> {
                if (tenantId == null || tenantRole == null) {
                    throw new IllegalArgumentException(
                            "TENANT_USER users must have both tenantId and tenantRole");
                }
                TenantId.requireValid(tenantId);
                if (operatorRole != null) {
                    throw new IllegalArgumentException(
                            "TENANT_USER users must not have an operatorRole");
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Factories — call these instead of the canonical constructor
    // ────────────────────────────────────────────────────────────────────

    public static User newOperator(
            String id,
            String email,
            String passwordHash,
            String firstName,
            String lastName,
            OperatorRole role) {
        var now = Instant.now();
        return new User(id, email, passwordHash, firstName, lastName,
                UserStatus.INVITED, UserKind.OPERATOR, role,
                null, null, 0, null, now, now);
    }

    public static User newTenantUser(
            String id,
            String email,
            String passwordHash,
            String firstName,
            String lastName,
            String tenantId,
            TenantRole role) {
        var now = Instant.now();
        return new User(id, email, passwordHash, firstName, lastName,
                UserStatus.INVITED, UserKind.TENANT_USER, null,
                tenantId, role, 0, null, now, now);
    }

    /** Convenience: returns true if this user can act inside the given tenant. */
    public boolean canAccess(String targetTenantId) {
        return switch (kind) {
            case TENANT_USER -> targetTenantId.equals(tenantId);
            // For OPERATOR users, "can access" requires checking the
            // operator_assignments collection — not derivable from this record alone.
            case OPERATOR -> throw new UnsupportedOperationException(
                    "Use OperatorAssignmentService to check operator access");
        };
    }
}
