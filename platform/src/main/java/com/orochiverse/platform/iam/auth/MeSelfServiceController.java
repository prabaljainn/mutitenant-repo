package com.orochiverse.platform.iam.auth;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.orochiverse.platform.common.security.auth.AuthenticatedUser;
import com.orochiverse.platform.iam.auth.AuthDtos.TokenResponse;
import com.orochiverse.platform.iam.auth.MeSelfServiceDtos.ChangePasswordRequest;
import com.orochiverse.platform.iam.auth.MeSelfServiceDtos.ProfileResponse;
import com.orochiverse.platform.iam.auth.MeSelfServiceDtos.RevokeOthersResponse;
import com.orochiverse.platform.iam.auth.MeSelfServiceDtos.SessionResponse;
import com.orochiverse.platform.iam.auth.MeSelfServiceDtos.UpdateProfileRequest;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Self-service for the authenticated user — applies to operators and
 * tenant users alike.
 *
 * <p>Shares the {@code /api/auth/me} prefix with the existing
 * {@link com.orochiverse.platform.common.security.auth.MeController} (which
 * just echoes the principal). Kept as a separate controller because the
 * write operations depend on {@link RefreshTokenStore}, {@code UserRepository},
 * etc. — all {@code iam.*} types that {@code common.security.auth} cannot
 * see by the package-boundary rule.
 *
 * <p>All endpoints require {@code isAuthenticated()} — the principal is
 * always the caller themselves. There is no admin override here.
 */
@RestController
@RequestMapping("/api/auth/me")
@ConditionalOnProperty(prefix = "spring.data.mongodb", name = "uri")
@Tag(name = "Auth", description = "Login, refresh, logout, switch-tenant, "
        + "forgot/reset password, accept invite. Public except where the "
        + "operation requires an existing session.")
public class MeSelfServiceController {

    private final MeSelfService service;

    public MeSelfServiceController(MeSelfService service) {
        this.service = service;
    }

    @GetMapping("/profile")
    @PreAuthorize("isAuthenticated()")
    public ProfileResponse getProfile(@AuthenticationPrincipal AuthenticatedUser caller) {
        return service.getProfile(caller.claims().userId());
    }

    @PatchMapping("/profile")
    @PreAuthorize("isAuthenticated()")
    public ProfileResponse updateProfile(@Valid @RequestBody UpdateProfileRequest req,
                                         @AuthenticationPrincipal AuthenticatedUser caller) {
        return service.updateProfile(caller.claims().userId(),
                req.firstName(), req.lastName());
    }

    /**
     * Returns a fresh access + refresh pair on success; the caller's
     * other sessions are revoked as a side-effect. Status: 200.
     */
    @PostMapping("/password")
    @PreAuthorize("isAuthenticated()")
    public TokenResponse changePassword(@Valid @RequestBody ChangePasswordRequest req,
                                        @AuthenticationPrincipal AuthenticatedUser caller,
                                        HttpServletRequest http) {
        return service.changePassword(caller.claims().userId(),
                req.currentPassword(), req.newPassword(),
                clientIp(http), http.getHeader("User-Agent"));
    }

    @GetMapping("/sessions")
    @PreAuthorize("isAuthenticated()")
    public List<SessionResponse> listSessions(@AuthenticationPrincipal AuthenticatedUser caller) {
        return service.listSessions(caller.claims().userId());
    }

    /** Idempotent: 204 whether or not the session id was known. */
    @DeleteMapping("/sessions/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> revokeSession(@PathVariable("id") String id,
                                              @AuthenticationPrincipal AuthenticatedUser caller) {
        service.revokeSession(caller.claims().userId(), id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    /**
     * "Sign out everywhere else." Revokes every session for the caller
     * except the one whose derived id is {@code keepId}. {@code keepId}
     * is required so the SPA can keep the calling tab alive — pass the
     * id the SPA already computes locally via
     * {@code RefreshTokenStore.deriveSessionId}.
     */
    @DeleteMapping("/sessions/others")
    @PreAuthorize("isAuthenticated()")
    public RevokeOthersResponse revokeOtherSessions(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @RequestParam(value = "keepId", required = false) String keepId) {
        int count = service.revokeOtherSessions(caller.claims().userId(), keepId);
        return new RevokeOthersResponse(count);
    }

    /**
     * Mirrors the X-Forwarded-For-aware extraction in
     * {@link AuthController#login}. Keeping a private copy avoids a
     * dependency from {@code iam.auth.me} on the sibling controller.
     */
    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return comma < 0 ? xff.trim() : xff.substring(0, comma).trim();
        }
        return req.getRemoteAddr();
    }
}
