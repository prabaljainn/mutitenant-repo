package com.orochiverse.platform.iam.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Request and response DTOs for {@code /api/auth/*}. Grouped in one file
 * because they're tiny records that only make sense together — splitting
 * them across six files would be more navigation than signal.
 *
 * <p>Bean-Validation annotations on request types fire automatically when
 * the controller method is annotated {@code @Valid}.
 */
public final class AuthDtos {

    private AuthDtos() {}

    public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}

    public record LogoutRequest(String refreshToken) {}

    public record SwitchTenantRequest(@NotBlank String tenantId) {}

    /**
     * {@code POST /api/auth/forgot-password}. Always returns 204 — we
     * never tell the caller whether the email exists, to prevent account
     * enumeration. The email gets sent only if a real ACTIVE user matches.
     */
    public record ForgotPasswordRequest(@Email @NotBlank String email) {}

    /**
     * {@code POST /api/auth/reset-password}. Both fields required.
     * Password complexity rules are intentionally minimal here (non-blank);
     * stronger rules belong in a separate validator that the SPA can also
     * enforce client-side.
     */
    public record ResetPasswordRequest(@NotBlank String token, @NotBlank String newPassword) {}

    /**
     * {@code POST /api/auth/accept-invite}. Same shape as reset; different
     * semantics — sets the password AND flips status from INVITED to
     * ACTIVE, then returns a {@link TokenResponse} so the user is logged
     * in in one step.
     */
    public record AcceptInviteRequest(@NotBlank String token, @NotBlank String newPassword) {}

    /**
     * Returned by {@code POST /login} and {@code POST /refresh}.
     *
     * @param accessToken    the signed JWT to send as {@code Authorization: Bearer ...}
     * @param refreshToken   opaque token to call {@code POST /refresh} with
     * @param expiresIn      seconds until the access token expires (RFC 6749 §5.1)
     * @param tokenType      always {@code "Bearer"}; included for client-library compat
     */
    public record TokenResponse(String accessToken, String refreshToken, long expiresIn, String tokenType) {
        public static TokenResponse bearer(String accessToken, String refreshToken, long expiresIn) {
            return new TokenResponse(accessToken, refreshToken, expiresIn, "Bearer");
        }
    }

    /**
     * Returned by {@code POST /switch-tenant}. Refresh token is unchanged
     * — the same session continues; only the active tenant changes.
     */
    public record SwitchTenantResponse(String accessToken, long expiresIn, String tokenType) {
        public static SwitchTenantResponse bearer(String accessToken, long expiresIn) {
            return new SwitchTenantResponse(accessToken, expiresIn, "Bearer");
        }
    }
}
