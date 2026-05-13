package com.orochiverse.platform.common.email;

import java.util.Map;

/**
 * Sends platform email. Two implementations:
 *
 * <ul>
 *   <li>{@link SmtpEmailService} — real SMTP via Spring {@code JavaMailSender}.
 *       Active when {@code spring.mail.host} is set (dev / integration / prod).</li>
 *   <li>{@link NoOpEmailService} — logs but doesn't send. Active in the
 *       {@code test} profile (mail autoconfig excluded → no
 *       {@code JavaMailSender} bean → SMTP impl backs off).</li>
 * </ul>
 *
 * <p>The {@code template} parameter names a Thymeleaf template under
 * {@code classpath:templates/email/<template>.txt}. Models are resolved
 * with the standard Thymeleaf TEXT-mode engine wired in
 * {@link EmailConfig}.
 */
public interface EmailService {

    /**
     * Render {@code template} with {@code model}, then send the result
     * to {@code to} with {@code subject}.
     */
    void send(String to, String subject, String template, Map<String, Object> model);

    /** Send an already-rendered {@link EmailMessage} verbatim. */
    void send(EmailMessage message);
}
