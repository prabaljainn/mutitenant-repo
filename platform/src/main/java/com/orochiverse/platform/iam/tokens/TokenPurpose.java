package com.orochiverse.platform.iam.tokens;

import java.time.Duration;

/**
 * What a {@link SingleUseToken} is for. The purpose pins both the lifetime
 * and the verifier — a token issued for {@link #INVITE_ACCEPT} is rejected
 * by the password-reset flow even if the random body matches.
 *
 * <p>Per-purpose TTLs are encoded here rather than in config so that the
 * security-relevant defaults are visible in code review and can't drift
 * via env tweaks: a 1-hour reset window is a deliberate trade-off between
 * "user can take a coffee break" and "leaked email link is dangerous".
 */
public enum TokenPurpose {

    /** Activates an INVITED user. Long-ish window so the email isn't a footgun. */
    INVITE_ACCEPT(Duration.ofDays(7)),

    /** Sets a new password for an existing user. Short window — leaked email is high-impact. */
    PASSWORD_RESET(Duration.ofHours(1));

    private final Duration ttl;

    TokenPurpose(Duration ttl) {
        this.ttl = ttl;
    }

    public Duration ttl() {
        return ttl;
    }
}
