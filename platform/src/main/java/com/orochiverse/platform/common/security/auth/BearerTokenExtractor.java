package com.orochiverse.platform.common.security.auth;

import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;

/**
 * Extracts the JWT from an {@code Authorization: Bearer <token>} request
 * header. Centralizing this in one tiny class keeps the auth filter free
 * of header-fiddling and gives us a single place to evolve the rule
 * (e.g. accept a cookie fallback later) without touching the filter.
 *
 * <p>Per RFC 6750 §2.1 the scheme name {@code Bearer} is matched
 * case-insensitively. We trim whitespace because some HTTP clients (and
 * humans pasting tokens) leave a trailing newline.
 */
public final class BearerTokenExtractor {

    private static final String SCHEME = "Bearer";

    private BearerTokenExtractor() {}

    public static Optional<String> extract(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        String trimmed = header.trim();
        if (trimmed.length() <= SCHEME.length() + 1) {
            return Optional.empty();
        }
        if (!trimmed.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
            return Optional.empty();
        }
        if (trimmed.charAt(SCHEME.length()) != ' ') {
            return Optional.empty();
        }
        String token = trimmed.substring(SCHEME.length() + 1).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }
}
