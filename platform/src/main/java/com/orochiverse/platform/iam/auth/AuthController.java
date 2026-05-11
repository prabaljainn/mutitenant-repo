package com.orochiverse.platform.iam.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orochiverse.platform.common.security.auth.AuthenticatedUser;
import com.orochiverse.platform.iam.auth.AuthDtos.AcceptInviteRequest;
import com.orochiverse.platform.iam.auth.AuthDtos.ForgotPasswordRequest;
import com.orochiverse.platform.iam.auth.AuthDtos.LoginRequest;
import com.orochiverse.platform.iam.auth.AuthDtos.LogoutRequest;
import com.orochiverse.platform.iam.auth.AuthDtos.RefreshRequest;
import com.orochiverse.platform.iam.auth.AuthDtos.ResetPasswordRequest;
import com.orochiverse.platform.iam.auth.AuthDtos.SwitchTenantRequest;
import com.orochiverse.platform.iam.auth.AuthDtos.SwitchTenantResponse;
import com.orochiverse.platform.iam.auth.AuthDtos.TokenResponse;

/**
 * REST surface for the {@code /api/auth/*} flows. Thin wrapper around
 * {@link AuthService}; the heavy lifting and audit are in the service.
 *
 * <p>{@code /login}, {@code /refresh} are permitted in {@code SecurityConfig};
 * {@code /logout} and {@code /switch-tenant} require an authenticated
 * caller (the JWT filter populates the SecurityContext before they're
 * reached).
 *
 * <p>{@code /me} lives separately at {@link com.orochiverse.platform.common.security.auth.MeController}
 * — kept in {@code common} because it predates {@code iam.auth} and has no
 * IAM dependency.
 */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(prefix = "spring.data.mongodb", name = "uri")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        return auth.login(req.email(), req.password(), clientIp(http));
    }

    /**
     * Trusts {@code X-Forwarded-For} when present (Spring Boot's
     * {@code server.forward-headers-strategy=framework} validates the chain).
     * Falls back to the direct socket address.
     */
    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // X-Forwarded-For: client, proxy1, proxy2 — first hop is the original.
            int comma = xff.indexOf(',');
            return comma < 0 ? xff.trim() : xff.substring(0, comma).trim();
        }
        return req.getRemoteAddr();
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest req) {
        return auth.refresh(req.refreshToken());
    }

    /**
     * Idempotent: returns 204 whether or not the supplied token was known.
     * The body is optional — a client can call /logout with no body to
     * clear server-side audit trail intent without revoking a specific
     * refresh token (useful when the refresh is already gone).
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody(required = false) LogoutRequest req) {
        auth.logout(req == null ? null : req.refreshToken());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @PostMapping("/switch-tenant")
    @PreAuthorize("hasRole('OPERATOR')")
    public SwitchTenantResponse switchTenant(@AuthenticationPrincipal AuthenticatedUser user,
                                             @Valid @RequestBody SwitchTenantRequest req) {
        return auth.switchTenant(user.claims().userId(), req.tenantId());
    }

    /**
     * Always 204 — never tells the caller whether the email exists, to
     * prevent account enumeration. The email is sent only when a real
     * ACTIVE user matches; the rest is silent.
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest req) {
        auth.requestPasswordReset(req.email());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    /**
     * 204 on success. {@link InvalidRefreshTokenException}-style 401 from
     * the {@link AuthExceptionHandler} on bad/expired/already-consumed
     * tokens. Does NOT log the user in — the SPA navigates to /login.
     */
    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        auth.resetPassword(req.token(), req.newPassword());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    /**
     * One-step onboarding: validates the invite, sets the password, flips
     * status to ACTIVE, and returns a fresh access + refresh pair so the
     * user lands logged in.
     */
    @PostMapping("/accept-invite")
    public TokenResponse acceptInvite(@Valid @RequestBody AcceptInviteRequest req) {
        return auth.acceptInvite(req.token(), req.newPassword());
    }
}
