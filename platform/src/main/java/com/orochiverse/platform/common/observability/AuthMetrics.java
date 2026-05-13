package com.orochiverse.platform.common.observability;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Application-specific Micrometer counters for auth and onboarding flows.
 * Exposed via {@code /actuator/prometheus}; dashboards / alerts pivot on
 * the {@code outcome} / {@code kind} / {@code stage} tags.
 *
 * <p>Counters are pre-registered in the constructor so the
 * {@code /prometheus} scrape returns 0 for known tags before the first
 * request — Prometheus alerting on {@code increase()} works from the
 * first scrape.
 */
@Component
public class AuthMetrics {

    private final Counter loginSuccess;
    private final Counter loginFailure;
    private final Counter loginRateLimited;
    private final Counter inviteOperator;
    private final Counter inviteTenantUser;
    private final Counter passwordResetRequested;
    private final Counter passwordResetCompleted;
    private final Counter tokenVersionMismatch;

    public AuthMetrics(MeterRegistry registry) {
        this.loginSuccess = login(registry, "success");
        this.loginFailure = login(registry, "failure");
        this.loginRateLimited = login(registry, "rate_limited");
        this.inviteOperator = invite(registry, "operator");
        this.inviteTenantUser = invite(registry, "tenant_user");
        this.passwordResetRequested = passwordReset(registry, "requested");
        this.passwordResetCompleted = passwordReset(registry, "completed");
        this.tokenVersionMismatch = Counter.builder("platform_token_version_check_failures_total")
                .description("Bearer rejected because the user's tokenVersion advanced past the claim's tv")
                .register(registry);
    }

    public void loginSuccess()      { loginSuccess.increment(); }
    public void loginFailure()      { loginFailure.increment(); }
    public void loginRateLimited()  { loginRateLimited.increment(); }
    public void inviteOperator()    { inviteOperator.increment(); }
    public void inviteTenantUser()  { inviteTenantUser.increment(); }
    public void passwordResetRequested() { passwordResetRequested.increment(); }
    public void passwordResetCompleted() { passwordResetCompleted.increment(); }
    public void tokenVersionMismatch()   { tokenVersionMismatch.increment(); }

    private static Counter login(MeterRegistry r, String outcome) {
        return Counter.builder("platform_login_attempts_total")
                .description("Login attempts by outcome (success, failure, rate_limited)")
                .tag("outcome", outcome)
                .register(r);
    }

    private static Counter invite(MeterRegistry r, String kind) {
        return Counter.builder("platform_invite_emails_total")
                .description("Invite emails sent by kind (operator, tenant_user)")
                .tag("kind", kind)
                .register(r);
    }

    private static Counter passwordReset(MeterRegistry r, String stage) {
        return Counter.builder("platform_password_resets_total")
                .description("Password reset events by stage (requested, completed)")
                .tag("stage", stage)
                .register(r);
    }
}
