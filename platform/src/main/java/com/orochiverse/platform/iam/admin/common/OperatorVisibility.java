package com.orochiverse.platform.iam.admin.common;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.orochiverse.platform.common.security.auth.AuthenticatedUser;
import com.orochiverse.platform.common.security.jwt.AccessTokenClaims;
import com.orochiverse.platform.common.security.principals.OperatorRole;
import com.orochiverse.platform.common.security.principals.UserKind;
import com.orochiverse.platform.iam.admin.common.AdminExceptions.NotFoundException;
import com.orochiverse.platform.iam.operators.OperatorAssignment;
import com.orochiverse.platform.iam.operators.OperatorAssignmentRepository;

/**
 * Decides whether the current operator can see a given tenant on the
 * {@code /admin/api/*} surface.
 *
 * <h2>Rule</h2>
 * <ul>
 *   <li>{@link OperatorRole#OPERATOR_ADMIN} — unrestricted: every tenant
 *       is visible.</li>
 *   <li>{@link OperatorRole#OPERATOR_SUPPORT} — scoped: only tenants the
 *       operator has an {@link OperatorAssignment} for.</li>
 * </ul>
 *
 * <h2>Why 404, not 403, on a missing assignment</h2>
 * Defense-in-depth: a 403 reveals that the tenant exists. We collapse
 * "tenant doesn't exist", "tenant soft-deleted", and "you can't see
 * this tenant" into the same {@link NotFoundException} so a curious
 * SUPPORT operator can't enumerate the customer list by probing ids.
 *
 * <p>The bean reads the principal off
 * {@link SecurityContextHolder} so callers don't need to thread claims
 * through every service method — same pattern as the existing
 * {@code TenantContext}.
 */
@Service
@ConditionalOnProperty(prefix = "spring.data.mongodb", name = "uri")
public class OperatorVisibility {

    private final OperatorAssignmentRepository assignments;

    public OperatorVisibility(OperatorAssignmentRepository assignments) {
        this.assignments = assignments;
    }

    /** True when the caller's authority lets them read any tenant. */
    public boolean isUnrestricted() {
        var c = currentClaims();
        return c.kind() == UserKind.OPERATOR
                && c.operatorRole() == OperatorRole.OPERATOR_ADMIN;
    }

    /**
     * Returns the set of tenant ids the caller may see. {@code null}
     * means "no restriction" (admins). An empty set means "you can see
     * nothing" (a SUPPORT operator with no assignments). Callers MUST
     * handle the {@code null} case explicitly to avoid accidentally
     * returning an empty list to admins.
     */
    public Set<String> visibleTenantIdsOrUnrestricted() {
        if (isUnrestricted()) return null;
        var c = currentClaims();
        if (c.kind() != UserKind.OPERATOR) return Set.of();
        return assignments.findAllByOperatorUserId(c.userId()).stream()
                .map(OperatorAssignment::tenantId)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** True when the caller may see this specific tenant. */
    public boolean canSee(String tenantId) {
        if (isUnrestricted()) return true;
        var c = currentClaims();
        if (c.kind() != UserKind.OPERATOR) return false;
        return assignments.existsByOperatorUserIdAndTenantId(c.userId(), tenantId);
    }

    /**
     * Throws 404 if the caller can't see this tenant. Use after the
     * existing "tenant exists" check on read endpoints.
     */
    public void requireVisibility(String tenantId) {
        if (!canSee(tenantId)) {
            throw new NotFoundException("tenant " + tenantId + " not found");
        }
    }

    private AccessTokenClaims currentClaims() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof AuthenticatedUser au) {
            return au.claims();
        }
        // Reaching here means a controller without @PreAuthorize — bug,
        // not a request to handle gracefully.
        throw new IllegalStateException(
                "OperatorVisibility called outside an authenticated operator request");
    }
}
