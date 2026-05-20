package com.orochiverse.platform.common.email;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;

class EmailRenderingTest {

    private final EmailConfig config = new EmailConfig();
    private final org.thymeleaf.TemplateEngine engine = config.emailTemplateEngine();

    @Test
    void invite_operator_template_renders_all_variables() {
        var ctx = new Context();
        ctx.setVariable("firstName", "Alice");
        ctx.setVariable("role", "OPERATOR_ADMIN");
        ctx.setVariable("acceptUrl", "http://localhost:8080/accept-invite?token=abc.def");
        ctx.setVariable("expiresAt", "2026-05-18T12:00:00Z");

        String body = engine.process("invite-operator", ctx);

        assertThat(body)
                .contains("Hi Alice,")
                .contains("OPERATOR_ADMIN")
                .contains("http://localhost:8080/accept-invite?token=abc.def")
                .contains("2026-05-18T12:00:00Z");
    }

    @Test
    void invite_tenant_user_template_includes_tenant_name() {
        var ctx = new Context();
        ctx.setVariable("firstName", "Bob");
        ctx.setVariable("tenantName", "Acme Corp");
        ctx.setVariable("role", "MEMBER");
        ctx.setVariable("acceptUrl", "http://localhost:8080/accept-invite?token=xyz");
        ctx.setVariable("expiresAt", "2026-05-18T12:00:00Z");

        String body = engine.process("invite-tenant-user", ctx);

        assertThat(body)
                .contains("Hi Bob,")
                .contains("Acme Corp")
                .contains("MEMBER")
                .contains("token=xyz");
    }

    @Test
    void password_reset_template_includes_reset_url_and_expiry() {
        var ctx = new Context();
        ctx.setVariable("firstName", "Carol");
        ctx.setVariable("email", "carol@example.com");
        ctx.setVariable("resetUrl", "http://localhost:8080/reset-password?token=reset123");
        ctx.setVariable("expiresAt", "2026-05-11T13:00:00Z");

        String body = engine.process("password-reset", ctx);

        assertThat(body)
                .contains("Hi Carol,")
                .contains("carol@example.com")
                .contains("token=reset123")
                .contains("2026-05-11T13:00:00Z");
    }

    @Test
    void smtp_email_service_renders_via_template_engine() {
        // Wire a SmtpEmailService with a fake JavaMailSender so we can
        // assert that the rendered body matches what we'd send.
        var sent = new java.util.ArrayList<org.springframework.mail.SimpleMailMessage>();
        var sender = new org.springframework.mail.javamail.JavaMailSenderImpl() {
            @Override public void send(org.springframework.mail.SimpleMailMessage simpleMessage) {
                sent.add(simpleMessage);
            }
        };
        var props = new EmailProperties("noreply@test.local", "support@test.local",
                "http://localhost:8080");
        var svc = new SmtpEmailService(sender, engine, props);

        svc.send("alice@example.com", "Welcome", "invite-operator", Map.of(
                "firstName", "Alice", "role", "OPERATOR_ADMIN",
                "acceptUrl", "http://x", "expiresAt", "2026-05-18T12:00:00Z"));

        assertThat(sent).hasSize(1);
        var msg = sent.get(0);
        assertThat(msg.getFrom()).isEqualTo("noreply@test.local");
        assertThat(msg.getReplyTo()).isEqualTo("support@test.local");
        assertThat(msg.getTo()).containsExactly("alice@example.com");
        assertThat(msg.getSubject()).isEqualTo("Welcome");
        assertThat(msg.getText()).contains("Hi Alice").contains("OPERATOR_ADMIN");
    }
}
