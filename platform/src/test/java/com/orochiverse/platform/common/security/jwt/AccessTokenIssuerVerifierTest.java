package com.orochiverse.platform.common.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.orochiverse.platform.common.security.keys.EphemeralRsaKeyProvider;
import com.orochiverse.platform.common.security.keys.JwtKeyProvider;
import com.orochiverse.platform.common.security.principals.OperatorRole;
import com.orochiverse.platform.common.security.principals.TenantRole;
import com.orochiverse.platform.common.security.principals.UserKind;

class AccessTokenIssuerVerifierTest {

    private static final String ISSUER = "https://iam.orochiverse.test";

    private JwtKeyProvider keys;
    private JwtProperties props;
    private Clock clock;
    private AccessTokenIssuer issuer;
    private AccessTokenVerifier verifier;

    @BeforeEach
    void setUp() {
        keys = new EphemeralRsaKeyProvider();
        props = new JwtProperties(ISSUER, Duration.ofMinutes(15), Duration.ofSeconds(30), null, null, null);
        clock = Clock.fixed(Instant.parse("2026-05-11T12:00:00Z"), ZoneOffset.UTC);
        issuer = new AccessTokenIssuer(keys, props, clock);
        verifier = new AccessTokenVerifier(keys, props);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Round-trip
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void operator_token_round_trips() {
        var issued = issuer.issue("op-1", "op@orochi.example", UserKind.OPERATOR,
                OperatorRole.OPERATOR_ADMIN, null, null, 0);

        var verified = verifier.verify(issued.token());

        assertThat(verified.userId()).isEqualTo("op-1");
        assertThat(verified.email()).isEqualTo("op@orochi.example");
        assertThat(verified.kind()).isEqualTo(UserKind.OPERATOR);
        assertThat(verified.operatorRole()).isEqualTo(OperatorRole.OPERATOR_ADMIN);
        assertThat(verified.activeTenantId()).isNull();
        assertThat(verified.tenantRole()).isNull();
        assertThat(verified.issuer()).isEqualTo(ISSUER);
        assertThat(verified.tokenVersion()).isZero();
        assertThat(verified.jti()).isNotBlank();
        assertThat(verified.issuedAt()).isEqualTo(Instant.parse("2026-05-11T12:00:00Z"));
        assertThat(verified.expiresAt()).isEqualTo(Instant.parse("2026-05-11T12:15:00Z"));
    }

    @Test
    void tenant_user_token_round_trips() {
        var issued = issuer.issue("tu-1", "bob@acme.example", UserKind.TENANT_USER,
                null, "acme", TenantRole.TENANT_OWNER, 7);

        var verified = verifier.verify(issued.token());

        assertThat(verified.kind()).isEqualTo(UserKind.TENANT_USER);
        assertThat(verified.activeTenantId()).isEqualTo("acme");
        assertThat(verified.tenantRole()).isEqualTo(TenantRole.TENANT_OWNER);
        assertThat(verified.operatorRole()).isNull();
        assertThat(verified.tokenVersion()).isEqualTo(7);
    }

    @Test
    void operator_in_a_switched_tenant_carries_tid_but_no_tRole() {
        var issued = issuer.issue("op-2", "op2@orochi.example", UserKind.OPERATOR,
                OperatorRole.OPERATOR_SUPPORT, "vega", null, 0);

        var verified = verifier.verify(issued.token());

        assertThat(verified.kind()).isEqualTo(UserKind.OPERATOR);
        assertThat(verified.activeTenantId()).isEqualTo("vega");
        assertThat(verified.tenantRole()).isNull();
        assertThat(verified.operatorRole()).isEqualTo(OperatorRole.OPERATOR_SUPPORT);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Negative cases
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void rejects_a_token_signed_by_a_different_key() {
        var foreignKeys = new EphemeralRsaKeyProvider();
        var foreignIssuer = new AccessTokenIssuer(foreignKeys, props, clock);
        var foreign = foreignIssuer.issue("op-x", "x@orochi.example", UserKind.OPERATOR,
                OperatorRole.OPERATOR_ADMIN, null, null, 0);

        assertThatThrownBy(() -> verifier.verify(foreign.token()))
                .isInstanceOf(JwtVerificationException.class);
    }

    @Test
    void rejects_an_expired_token() throws InterruptedException {
        // TTL of 1 ms + 0 skew, then sleep past it. The verifier reads the
        // embedded `exp` against the system clock (jjwt's parser API doesn't
        // expose a clock injection point in 0.12), so a real sleep is the
        // simplest reliable expiry assertion.
        var shortProps = new JwtProperties(ISSUER, Duration.ofMillis(1), Duration.ofMillis(0), null, null, null);
        var shortIssuer = new AccessTokenIssuer(keys, shortProps, Clock.systemUTC());
        var shortVerifier = new AccessTokenVerifier(keys, shortProps);
        var shortIssued = shortIssuer.issue("op-1", "op@orochi.example", UserKind.OPERATOR,
                OperatorRole.OPERATOR_ADMIN, null, null, 0);

        Thread.sleep(50);

        assertThatThrownBy(() -> shortVerifier.verify(shortIssued.token()))
                .isInstanceOf(JwtVerificationException.class);
    }

    @Test
    void rejects_a_token_with_wrong_issuer() {
        var foreignProps = new JwtProperties("https://iam.someone-else.example",
                Duration.ofMinutes(15), Duration.ofSeconds(30), null, null, null);
        var foreignIssuer = new AccessTokenIssuer(keys, foreignProps, clock);
        var foreign = foreignIssuer.issue("op-1", "op@orochi.example", UserKind.OPERATOR,
                OperatorRole.OPERATOR_ADMIN, null, null, 0);

        assertThatThrownBy(() -> verifier.verify(foreign.token()))
                .isInstanceOf(JwtVerificationException.class);
    }

    @Test
    void rejects_garbage_input() {
        assertThatThrownBy(() -> verifier.verify("not.a.jwt"))
                .isInstanceOf(JwtVerificationException.class);
        assertThatThrownBy(() -> verifier.verify(""))
                .isInstanceOf(JwtVerificationException.class);
        assertThatThrownBy(() -> verifier.verify(null))
                .isInstanceOf(JwtVerificationException.class);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Claims invariants
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void operator_kind_requires_opRole() {
        assertThatThrownBy(() -> issuer.issue("op-1", "op@orochi.example", UserKind.OPERATOR,
                null, null, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operatorRole");
    }

    @Test
    void tenant_user_kind_requires_tid_and_tRole() {
        assertThatThrownBy(() -> issuer.issue("tu-1", "x@x.example", UserKind.TENANT_USER,
                null, null, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("activeTenantId");
    }
}
