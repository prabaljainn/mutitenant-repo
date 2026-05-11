package com.orochiverse.platform.common.security.auth;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orochiverse.platform.common.tenant.TenantContext;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * {@code GET /api/auth/me} — returns the principal Spring resolved from the
 * incoming Bearer JWT.
 *
 * <p>Lives in {@code common.security.auth} (not {@code iam.auth}) for two
 * reasons: (a) it's a thin debug/inspection endpoint that doesn't touch
 * IAM internals — it just echoes whatever the filter put in the
 * SecurityContext; (b) the {@code common} → {@code iam} dependency is
 * forbidden by the package-boundary test, so keeping a self-contained
 * "show me the principal" endpoint here lets the auth filter be exercised
 * end-to-end before {@code iam.auth} (Phase 1.7) exists.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth", description = "Login, refresh, logout, switch-tenant, "
        + "forgot/reset password, accept invite. Public except where the "
        + "operation requires an existing session.")
public class MeController {

    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal AuthenticatedUser user) {
        var c = user.claims();

        var body = new LinkedHashMap<String, Object>();
        body.put("userId", c.userId());
        body.put("email", c.email());
        body.put("kind", c.kind().name());
        body.put("operatorRole", c.operatorRole() == null ? null : c.operatorRole().name());
        body.put("activeTenantId", c.activeTenantId());
        body.put("tenantRole", c.tenantRole() == null ? null : c.tenantRole().name());
        body.put("tokenVersion", c.tokenVersion());
        body.put("tokenId", c.jti());
        body.put("expiresAt", c.expiresAt().toString());
        // Sanity field: prove that the JWT filter actually bound tenant
        // context for this request when it should have. Useful in
        // integration tests; harmless in production.
        body.put("tenantContextBound", TenantContext.isBound());
        return body;
    }
}
