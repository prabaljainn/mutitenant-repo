package com.orochiverse.platform.common.email;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Spring {@link JavaMailSender}-backed implementation. Renders the
 * {@code template} with Thymeleaf in TEXT mode, then ships it via SMTP.
 *
 * <p>Gated on {@code @ConditionalOnProperty(spring.mail.host)} — the same
 * deterministic property-based pattern the rest of the codebase uses for
 * conditional Spring components. {@code @ConditionalOnBean} on a
 * {@code @Component} is fragile because the component-scan order is not
 * guaranteed to follow autoconfig.
 *
 * <p>When {@code spring.mail.host} is set (dev / integration / prod),
 * SMTP is the active {@link EmailService}; otherwise the
 * {@link NoOpEmailService} fallback wins via
 * {@code @ConditionalOnMissingBean(EmailService.class)} in
 * {@link EmailConfig}.
 *
 * <p>SMTP failures throw — callers should catch and decide whether to
 * fail the user-facing operation (rare; usually we want the user
 * created even if the invite email bounced) or just log and continue.
 * The current invite flow logs and continues.
 */
@Component
@ConditionalOnProperty(prefix = "spring.mail", name = "host")
public class SmtpEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailService.class);

    private final JavaMailSender sender;
    private final TemplateEngine templateEngine;
    private final EmailProperties props;

    public SmtpEmailService(JavaMailSender sender,
                            // Qualified explicitly: Spring Boot autoconfigures its own
                            // TemplateEngine for HTML view rendering at templates/, and
                            // unqualified injection grabs that one instead of ours.
                            @Qualifier("emailTemplateEngine") TemplateEngine templateEngine,
                            EmailProperties props) {
        this.sender = sender;
        this.templateEngine = templateEngine;
        this.props = props;
    }

    @Override
    public void send(String to, String subject, String template, Map<String, Object> model) {
        Context ctx = new Context();
        if (model != null) {
            model.forEach(ctx::setVariable);
        }
        String body = templateEngine.process(template, ctx);
        send(new EmailMessage(to, subject, body));
    }

    @Override
    public void send(EmailMessage message) {
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(props.from());
        if (props.replyTo() != null && !props.replyTo().isBlank()) {
            m.setReplyTo(props.replyTo());
        }
        m.setTo(message.to());
        m.setSubject(message.subject());
        m.setText(message.body());
        sender.send(m);
        log.info("email sent to={} subject={}", message.to(), message.subject());
    }
}
