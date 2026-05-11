package com.orochiverse.platform.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class AuthMetricsTest {

    @Test
    void counters_are_pre_registered_with_tags_so_prometheus_sees_zero_first() {
        var registry = new SimpleMeterRegistry();
        new AuthMetrics(registry);

        // Even with zero increments, the meters exist with the right tag set.
        assertThat(registry.find("platform_login_attempts_total").tag("outcome", "success").counter())
                .isNotNull();
        assertThat(registry.find("platform_login_attempts_total").tag("outcome", "failure").counter())
                .isNotNull();
        assertThat(registry.find("platform_login_attempts_total").tag("outcome", "rate_limited").counter())
                .isNotNull();
        assertThat(registry.find("platform_invite_emails_total").tag("kind", "operator").counter())
                .isNotNull();
        assertThat(registry.find("platform_invite_emails_total").tag("kind", "tenant_user").counter())
                .isNotNull();
        assertThat(registry.find("platform_password_resets_total").tag("stage", "requested").counter())
                .isNotNull();
        assertThat(registry.find("platform_password_resets_total").tag("stage", "completed").counter())
                .isNotNull();
        assertThat(registry.find("platform_token_version_check_failures_total").counter()).isNotNull();
    }

    @Test
    void increment_methods_bump_the_right_meter() {
        var registry = new SimpleMeterRegistry();
        var m = new AuthMetrics(registry);

        m.loginSuccess();
        m.loginSuccess();
        m.loginFailure();
        m.loginRateLimited();
        m.inviteOperator();
        m.inviteTenantUser();
        m.passwordResetRequested();
        m.passwordResetCompleted();
        m.tokenVersionMismatch();

        assertThat(registry.find("platform_login_attempts_total").tag("outcome", "success").counter().count())
                .isEqualTo(2.0);
        assertThat(registry.find("platform_login_attempts_total").tag("outcome", "failure").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.find("platform_login_attempts_total").tag("outcome", "rate_limited").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.find("platform_invite_emails_total").tag("kind", "operator").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.find("platform_invite_emails_total").tag("kind", "tenant_user").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.find("platform_password_resets_total").tag("stage", "requested").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.find("platform_password_resets_total").tag("stage", "completed").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.find("platform_token_version_check_failures_total").counter().count())
                .isEqualTo(1.0);
    }
}
