package com.orochiverse.platform.common.email;

import java.util.Objects;

/**
 * One outgoing message. Plain-text only in M1 — modern clients render
 * text fine and HTML adds rendering / tracking-pixel surface area we
 * don't need yet. HTML can plug in via a parallel field later without
 * changing the {@link EmailService} contract.
 */
public record EmailMessage(String to, String subject, String body) {

    public EmailMessage {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(body, "body");
        if (to.isBlank() || subject.isBlank() || body.isBlank()) {
            throw new IllegalArgumentException("to / subject / body must be non-blank");
        }
    }
}
