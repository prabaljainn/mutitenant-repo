package com.orochiverse.platform.common.email;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs the email instead of sending it. Used in environments without a
 * configured SMTP server — primarily the {@code test} profile (which
 * excludes the mail autoconfig) but also any deployment where you want
 * to disable email at the boundary without touching call sites.
 *
 * <p>The body shows up at INFO so failed-to-send behavior is at least
 * visible. The renderer is intentionally not invoked — this implementation
 * doesn't take the Thymeleaf template engine as a dependency, so it can
 * load even when no template engine is available.
 */
public class NoOpEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(NoOpEmailService.class);

    @Override
    public void send(String to, String subject, String template, Map<String, Object> model) {
        log.info("[NoOpEmailService] would send template={} to={} subject={} model-keys={}",
                template, to, subject, model == null ? "[]" : model.keySet());
    }

    @Override
    public void send(EmailMessage message) {
        log.info("[NoOpEmailService] would send to={} subject={}\n----- BODY -----\n{}\n----- END -----",
                message.to(), message.subject(), message.body());
    }
}
