package com.orochiverse.platform.iam.auth;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.orochiverse.platform.iam.users.User;

/**
 * Request and response DTOs for the operator self-service surface at
 * {@code /api/auth/me/profile|password|sessions}. Grouped here because
 * each is a one-line record that only makes sense alongside the others.
 */
public final class MeSelfServiceDtos {

    private MeSelfServiceDtos() {}

    /**
     * Partial — {@code null} fields are left alone. {@link Size} validation
     * fires only when the field is present; that's the standard
     * Bean-Validation behaviour for nullable fields.
     */
    public record UpdateProfileRequest(
            @Size(min = 1, max = 100) String firstName,
            @Size(min = 1, max = 100) String lastName) {}

    public record ProfileResponse(
            String userId,
            String email,
            String firstName,
            String lastName) {

        public static ProfileResponse from(User u) {
            return new ProfileResponse(u.id(), u.email(), u.firstName(), u.lastName());
        }
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank String newPassword) {}

    /**
     * One row in {@code GET /api/auth/me/sessions}. {@code id} is the
     * stable 16-hex SHA-256-prefix of the opaque refresh token —
     * irreversible, safe to expose, and computable client-side so the SPA
     * can highlight the row matching its own session.
     */
    public record SessionResponse(
            String id,
            Instant issuedAt,
            Instant expiresAt) {}
}
