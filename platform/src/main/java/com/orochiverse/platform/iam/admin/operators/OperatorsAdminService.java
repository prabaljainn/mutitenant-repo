package com.orochiverse.platform.iam.admin.operators;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.orochiverse.platform.common.audit.AuditAction;
import com.orochiverse.platform.common.audit.AuditEntry;
import com.orochiverse.platform.common.audit.AuditEntryRepository;
import com.orochiverse.platform.common.email.EmailProperties;
import com.orochiverse.platform.common.email.EmailService;
import com.orochiverse.platform.common.observability.AuthMetrics;
import com.orochiverse.platform.common.security.principals.UserKind;
import com.orochiverse.platform.iam.admin.common.AdminExceptions.ConflictException;
import com.orochiverse.platform.iam.admin.common.AdminExceptions.NotFoundException;
import com.orochiverse.platform.iam.admin.common.AdminExceptions.UnprocessableException;
import com.orochiverse.platform.iam.admin.operators.OperatorDtos.InviteOperatorRequest;
import com.orochiverse.platform.iam.admin.operators.OperatorDtos.OperatorResponse;
import com.orochiverse.platform.iam.admin.operators.OperatorDtos.UpdateOperatorRequest;
import com.orochiverse.platform.iam.auth.RefreshTokenStore;
import com.orochiverse.platform.iam.tokens.SingleUseToken;
import com.orochiverse.platform.iam.tokens.SingleUseTokenStore;
import com.orochiverse.platform.iam.tokens.TokenPurpose;
import com.orochiverse.platform.iam.users.User;
import com.orochiverse.platform.iam.users.UserRepository;
import com.orochiverse.platform.iam.users.UserStatus;

/**
 * Operator-user lifecycle: invite → activate (out-of-band) → optionally
 * suspend / role-change / soft-delete.
 *
 * <h2>Invite flow (this phase)</h2>
 * <ol>
 *   <li>Reject if email already exists in {@code iam_db.users}.</li>
 *   <li>Create the user with {@link UserStatus#INVITED}, no password.</li>
 *   <li>Audit {@code OPERATOR_INVITED}.</li>
 *   <li>(Phase 1.9 will email the invitee a link that lets them set a
 *       password and flips their status to {@link UserStatus#ACTIVE}.)</li>
 * </ol>
 *
 * <h2>Status side-effects</h2>
 * Any transition <em>away from</em> {@code ACTIVE} also revokes every
 * outstanding refresh token for the user — otherwise a suspended operator
 * would keep getting fresh access tokens for up to the refresh TTL.
 *
 * <h2>Why no hard delete?</h2>
 * Audit rows reference {@code actorUserId}; assignments reference
 * {@code operatorUserId}. Removing the user document would orphan both.
 * Soft-delete (status=DELETED) preserves historical context and lets us
 * lazily reclaim the email on a future invite.
 */
@Service
@ConditionalOnProperty(prefix = "spring.data.mongodb", name = "uri")
public class OperatorsAdminService {

    private static final Logger log = LoggerFactory.getLogger(OperatorsAdminService.class);

    private final UserRepository users;
    private final RefreshTokenStore refreshTokens;
    private final SingleUseTokenStore singleUseTokens;
    private final AuditEntryRepository audit;
    private final EmailService email;
    private final EmailProperties emailProps;
    private final AuthMetrics metrics;
    private final org.springframework.beans.factory.ObjectProvider<
            com.orochiverse.platform.common.security.auth.TokenVersionLookup> tvResolver;

    public OperatorsAdminService(UserRepository users,
                                 RefreshTokenStore refreshTokens,
                                 SingleUseTokenStore singleUseTokens,
                                 AuditEntryRepository audit,
                                 EmailService email,
                                 EmailProperties emailProps,
                                 AuthMetrics metrics,
                                 org.springframework.beans.factory.ObjectProvider<
                                         com.orochiverse.platform.common.security.auth.TokenVersionLookup> tvResolver) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.singleUseTokens = singleUseTokens;
        this.audit = audit;
        this.email = email;
        this.emailProps = emailProps;
        this.metrics = metrics;
        this.tvResolver = tvResolver;
    }

    private void invalidateTvCache(String userId) {
        var resolver = tvResolver.getIfAvailable();
        if (resolver != null) {
            resolver.invalidate(userId);
        }
    }

    public OperatorResponse invite(InviteOperatorRequest req, String actorUserId) {
        if (users.existsByEmailIgnoreCase(req.email())) {
            throw new ConflictException("user with email " + req.email() + " already exists");
        }

        // Stable-ish id: operator-<uuid8>. Random-ish so it's not guessable
        // and deterministic in length so reports look uniform.
        String id = "operator-" + UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();

        var invited = new User(
                id, req.email(), null, // null passwordHash — invite-accept flow sets it
                req.firstName(), req.lastName(),
                UserStatus.INVITED, UserKind.OPERATOR, req.role(),
                null, null, 0, null, now, now);

        User saved;
        try {
            saved = users.save(invited);
        } catch (DuplicateKeyException e) {
            // Race on the unique email index.
            throw new ConflictException("user with email " + req.email() + " already exists");
        }

        // Issue a single-use accept token (7-day TTL, see TokenPurpose) and
        // email the link. Failing to send must NOT roll back the user
        // creation — admins can re-issue invites manually if SMTP is down.
        SingleUseToken accept = singleUseTokens.issue(id, TokenPurpose.INVITE_ACCEPT);
        try {
            sendOperatorInviteEmail(saved, req.role().name(), accept);
        } catch (RuntimeException e) {
            log.warn("operator invite created but email failed id={} email={}: {}",
                    id, req.email(), e.getMessage());
        }

        audit.save(AuditEntry.of(AuditAction.OPERATOR_INVITED, actorUserId,
                Map.of("operatorId", id, "email", req.email(), "role", req.role().name())));
        metrics.inviteOperator();
        log.info("operator invited id={} email={} role={} actor={}",
                id, req.email(), req.role(), actorUserId);
        return OperatorResponse.from(saved);
    }

    private void sendOperatorInviteEmail(User user, String role, SingleUseToken accept) {
        String acceptUrl = emailProps.baseUrl() + "/accept-invite?token=" + accept.token();
        email.send(user.email(),
                "You're invited as an Orochiverse operator",
                "invite-operator",
                Map.of(
                        "firstName", user.firstName(),
                        "role", role,
                        "acceptUrl", acceptUrl,
                        "expiresAt", accept.expiresAt().toString()));
    }

    public List<OperatorResponse> list(UserStatus statusFilter) {
        var status = statusFilter == null ? UserStatus.ACTIVE : statusFilter;
        return users.findAllByKindAndStatus(UserKind.OPERATOR, status).stream()
                .map(OperatorResponse::from).toList();
    }

    public OperatorResponse get(String id) {
        return OperatorResponse.from(loadOperatorOrThrow(id));
    }

    public OperatorResponse update(String id, UpdateOperatorRequest req, String actorUserId) {
        User existing = loadOperatorOrThrow(id);

        String firstName = req.firstName() == null ? existing.firstName() : req.firstName();
        String lastName = req.lastName() == null ? existing.lastName() : req.lastName();
        var role = req.role() == null ? existing.operatorRole() : req.role();
        var newStatus = req.status() == null ? existing.status() : req.status();

        if (newStatus == UserStatus.DELETED) {
            throw new UnprocessableException(
                    "use DELETE /admin/api/operators/{id} to soft-delete an operator");
        }

        // Phase 1.10's TokenVersionLookup hook: any privilege-changing
        // edit bumps tokenVersion so in-flight access tokens are
        // rejected on their next request. Cosmetic edits (name) don't.
        boolean roleChanged = req.role() != null && req.role() != existing.operatorRole();
        boolean leftActive  = req.status() != null && req.status() != existing.status()
                                                  && req.status() != UserStatus.ACTIVE;
        boolean privilegeChange = roleChanged || leftActive;
        int newTv = privilegeChange ? existing.tokenVersion() + 1 : existing.tokenVersion();

        var updated = new User(
                existing.id(), existing.email(), existing.passwordHash(),
                firstName, lastName,
                newStatus, existing.kind(), role,
                null, null,
                newTv, existing.lastLoginAt(),
                existing.createdAt(), Instant.now());
        var saved = users.save(updated);
        if (privilegeChange) {
            invalidateTvCache(id);
        }

        if (roleChanged) {
            audit.save(AuditEntry.of(AuditAction.OPERATOR_ROLE_CHANGED, actorUserId,
                    Map.of("operatorId", id, "from", existing.operatorRole().name(),
                            "to", req.role().name())));
        }
        if (req.status() != null && req.status() != existing.status()
                && req.status() == UserStatus.SUSPENDED) {
            refreshTokens.revokeAllForUser(id);
            singleUseTokens.revokeAllForUser(id);
            audit.save(AuditEntry.of(AuditAction.OPERATOR_SUSPENDED, actorUserId,
                    Map.of("operatorId", id)));
        }
        return OperatorResponse.from(saved);
    }

    public void softDelete(String id, String actorUserId) {
        User existing = loadOperatorOrThrow(id);
        if (existing.id().equals(actorUserId)) {
            throw new UnprocessableException("operators cannot delete themselves");
        }

        // Scramble the email so the unique index slot is freed for a future
        // invite. The original lives on in the audit metadata below.
        String originalEmail = existing.email();
        var deleted = new User(
                existing.id(), User.deletedEmailMarker(existing.id()), existing.passwordHash(),
                existing.firstName(), existing.lastName(),
                UserStatus.DELETED, existing.kind(), existing.operatorRole(),
                null, null,
                existing.tokenVersion() + 1, existing.lastLoginAt(),
                existing.createdAt(), Instant.now());
        users.save(deleted);
        invalidateTvCache(id);

        refreshTokens.revokeAllForUser(id);
        singleUseTokens.revokeAllForUser(id);

        audit.save(AuditEntry.of(AuditAction.OPERATOR_DELETED, actorUserId,
                Map.of("operatorId", id, "email", originalEmail)));
        log.info("operator deleted id={} actor={}", id, actorUserId);
    }

    private User loadOperatorOrThrow(String id) {
        User u = users.findById(id)
                .orElseThrow(() -> new NotFoundException("operator " + id + " not found"));
        if (u.kind() != UserKind.OPERATOR) {
            throw new NotFoundException("user " + id + " is not an operator");
        }
        return u;
    }
}
