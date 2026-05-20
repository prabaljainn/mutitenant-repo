package com.orochiverse.platform.iam.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.orochiverse.platform.common.security.auth.AuthenticatedUser;
import com.orochiverse.platform.iam.auth.AuthDtos.AcceptInviteRequest;
import com.orochiverse.platform.iam.auth.AuthDtos.ForgotPasswordRequest;
import com.orochiverse.platform.iam.auth.AuthDtos.LoginRequest;
import com.orochiverse.platform.iam.auth.AuthDtos.LogoutRequest;
import com.orochiverse.platform.iam.auth.AuthDtos.OAuthTokenResponse;
import com.orochiverse.platform.iam.auth.AuthDtos.RefreshRequest;
import com.orochiverse.platform.iam.auth.AuthDtos.ResetPasswordRequest;
import com.orochiverse.platform.iam.auth.AuthDtos.SwitchTenantRequest;
import com.orochiverse.platform.iam.auth.AuthDtos.SwitchTenantResponse;
import com.orochiverse.platform.iam.auth.AuthDtos.TokenResponse;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.tags.Tag;

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
@Tag(name = "Auth", description = "Login, refresh, logout, switch-tenant, "
        + "forgot/reset password, accept invite. Public except where the "
        + "operation requires an existing session.")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        return auth.login(req.email(), req.password(), clientIp(http), userAgent(http));
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

    /** Raw User-Agent header, used to label sessions in the Account UI. */
    private static String userAgent(HttpServletRequest req) {
        return req.getHeader("User-Agent");
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest req, HttpServletRequest http) {
        return auth.refresh(req.refreshToken(), clientIp(http), userAgent(http));
    }

    /**
     * RFC 6749 §4.3 password-grant token endpoint, used exclusively by
     * Swagger UI's built-in OAuth2 client (see
     * {@link com.orochiverse.platform.common.openapi.OpenApiConfig}).
     * Accepts {@code application/x-www-form-urlencoded} and returns the
     * RFC 6749 §5.1 snake_case envelope so Swagger UI's "Authorize" form
     * can drive the login inline.
     *
     * <p>The regular SPA login flow stays on {@code /api/auth/login}
     * (JSON, camelCase). This endpoint is a thin adapter over the same
     * {@link AuthService#login} call — it does not introduce a separate
     * credential or rate-limit path.
     *
     * <p>Hidden from the operations list because Swagger UI invokes it
     * via the OAuth2 security scheme's {@code tokenUrl}, not via the
     * usual "Try it out" affordance — surfacing it twice would be noise.
     */
    @Hidden
    @PostMapping(value = "/oauth-token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public OAuthTokenResponse oauthToken(@RequestParam("grant_type") String grantType,
                                         @RequestParam("username") String username,
                                         @RequestParam("password") String password,
                                         HttpServletRequest http) {
        if (!"password".equalsIgnoreCase(grantType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "unsupported_grant_type: only 'password' is accepted here");
        }
        TokenResponse t = auth.login(username, password, clientIp(http), userAgent(http));
        return OAuthTokenResponse.from(t);
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
    public TokenResponse acceptInvite(@Valid @RequestBody AcceptInviteRequest req,
                                      HttpServletRequest http) {
        return auth.acceptInvite(req.token(), req.newPassword(), clientIp(http), userAgent(http));
    }
}
