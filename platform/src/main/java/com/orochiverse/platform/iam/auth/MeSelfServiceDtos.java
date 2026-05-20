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
     *
     * <p>{@code userAgent} and {@code ip} are best-effort: they may be
     * {@code null} for sessions minted before the field existed or rotated
     * in a context with no inbound request (e.g. password-change). The
     * UI is expected to fall back to a generic "Unknown device" label
     * when either is missing. {@code firstSeenAt} is preserved across
     * token rotation so the UI can show "signed in N days ago" even
     * though the underlying token rotated minutes ago.
     */
    public record SessionResponse(
            String id,
            Instant issuedAt,
            Instant firstSeenAt,
            Instant expiresAt,
            String userAgent,
            String ip) {}

    /**
     * Result of {@code DELETE /api/auth/me/sessions/others}. {@code count}
     * is what was revoked (excluding the caller's own session), letting
     * the UI render a precise confirmation like
     * "Signed out of 3 other devices".
     */
    public record RevokeOthersResponse(int count) {}
}
