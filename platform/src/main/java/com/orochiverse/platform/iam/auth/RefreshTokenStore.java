package com.orochiverse.platform.iam.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Storage interface for refresh tokens. Lives behind an interface so the
 * Phase 1.7 {@link InMemoryRefreshTokenStore} (process-local; tokens vanish
 * on restart) can be replaced with a Redis-backed implementation in
 * Phase 1.10 without touching {@link AuthService}.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link #issue(String, String, String, Instant)} mints a fresh
 *       random 256-bit token, records it as belonging to {@code userId},
 *       and returns it.</li>
 *   <li>{@link #consume(String)} atomically looks up the token, removes it
 *       from the store, and returns the bound entry — i.e. consume-on-use
 *       so a leaked refresh token is single-shot. Returns empty if missing
 *       or expired.</li>
 *   <li>{@link #revoke(String)} explicit cancellation (logout); idempotent.</li>
 *   <li>{@link #revokeAllForUser(String)} blunt instrument used when the
 *       user's password changes or the account is suspended.</li>
 *   <li>{@link #revokeAllForUserExcept(String, String)} powers the
 *       "sign out everywhere else" affordance on the Account page —
 *       keeps the caller's own session alive.</li>
 * </ol>
 *
 * <p>Refresh tokens are NOT JWTs — they're opaque random strings. The store
 * is the only place that knows the user binding.
 *
 * <h2>Self-service "active sessions"</h2>
 * The {@link #listForUser(String)} and {@link #revokeByIdForUser(String, String)}
 * pair powers the operator self-service Sessions UI. Sessions are identified
 * by a stable, irreversible derived id ({@link #deriveSessionId(String)}) so
 * the raw refresh token never leaves the server.
 */
public interface RefreshTokenStore {

    /**
     * Mint and persist a fresh refresh token for {@code userId}, optionally
     * tagging it with the request's user-agent + IP and the
     * {@code firstSeenAt} timestamp inherited from a previous rotated
     * token. All three are nullable: pass {@code null} when the call site
     * has no request scope (e.g. self-service password change running
     * inside a service method) — the store will substitute sensible
     * defaults.
     *
     * @param firstSeenAt when this session originally started; on rotation
     *                    the caller passes the previous token's
     *                    {@code firstSeenAt} so the UI can keep showing
     *                    "signed in 3 days ago" across refreshes. Pass
     *                    {@code null} for a brand-new session.
     */
    RefreshToken issue(String userId, String userAgent, String ip, Instant firstSeenAt);

    /**
     * Convenience overload — no session-context metadata. Used by tests
     * and by the in-process rotation triggered from
     * {@link MeSelfService#changePassword(String, String, String)} where
     * the new session is intentionally a fresh start with no carried-over
     * device info.
     */
    default RefreshToken issue(String userId) {
        return issue(userId, null, null, null);
    }

    /**
     * Atomically look up + remove the token. Returns the bound entry on
     * success; empty if the token is unknown or already expired. Removing
     * on read is the rotation primitive — the caller mints a new one to
     * hand back to the client.
     */
    Optional<RefreshToken> consume(String token);

    /** Idempotent best-effort revocation. Used by {@code POST /logout}. */
    void revoke(String token);

    /** Used on password change / account deactivation. */
    void revokeAllForUser(String userId);

    /**
     * Revoke every session for {@code userId} except the one whose
     * derived id matches {@code keepSessionId}. Returns the number of
     * revoked sessions (not counting the kept one). If {@code keepSessionId}
     * is {@code null} or matches no row, this degenerates to
     * {@link #revokeAllForUser(String)}.
     */
    int revokeAllForUserExcept(String userId, String keepSessionId);

    /**
     * Snapshot of one outstanding refresh token, safe to expose. The
     * {@code id} is derived from {@link #deriveSessionId(String)} so the
     * raw token never appears in the response. {@code userAgent} and
     * {@code ip} are best-effort: they may be {@code null} for sessions
     * minted before the field existed or rotated in a context with no
     * request (e.g. password-change rotation).
     */
    record SessionInfo(
            String id,
            Instant issuedAt,
            Instant firstSeenAt,
            Instant expiresAt,
            String userAgent,
            String ip) {}

    /**
     * Lists the live (non-expired) sessions belonging to {@code userId},
     * sorted newest-first. Used by {@code GET /api/auth/me/sessions}.
     */
    List<SessionInfo> listForUser(String userId);

    /**
     * Revoke a single session by its derived id. Returns {@code true} if a
     * matching session was found and removed; {@code false} otherwise.
     * Constrained to {@code userId} so an attacker who somehow learns
     * another user's session id can't revoke them — and so the obvious
     * forge-attempts (lifting an id from a log) are no-ops.
     */
    boolean revokeByIdForUser(String id, String userId);

    /**
     * Derive a 16-char hex session id from the opaque refresh token. The
     * id is the first 64 bits of SHA-256 — irreversible, collision-safe
     * for the scale of one user's outstanding sessions (dozens), and
     * deterministic so the client can recompute its own current session
     * id to highlight it in the UI.
     */
    static String deriveSessionId(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(token.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i] & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing from JRE", e);
        }
    }
}
