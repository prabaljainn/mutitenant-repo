package com.orochiverse.platform.common.email;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from the {@code platform.email} prefix.
 *
 * <pre>{@code
 *   platform:
 *     email:
 *       from: noreply@orochiverse.local
 *       reply-to: support@orochiverse.local
 *       base-url: http://localhost:8080
 * }</pre>
 *
 * <p>{@code baseUrl} is the host clients should hit when they click the
 * link in an invite or reset email. Distinct from {@code spring.mail.host}
 * (the SMTP server) — that's where outgoing email goes; this is where the
 * link in the email points.
 */
@ConfigurationProperties(prefix = "platform.email")
public record EmailProperties(String from, String replyTo, String baseUrl) {

    public EmailProperties {
        if (from == null || from.isBlank()) {
            throw new IllegalArgumentException("platform.email.from must be set");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("platform.email.base-url must be set");
        }
        // Normalize: drop trailing slash so we can concatenate cleanly.
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
    }
}
