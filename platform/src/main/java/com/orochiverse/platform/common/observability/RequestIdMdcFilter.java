package com.orochiverse.platform.common.observability;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Front-of-chain filter that attaches a request-id to MDC for the entire
 * request lifetime and echoes it back as {@code X-Request-Id}.
 *
 * <p>Runs at {@link Ordered#HIGHEST_PRECEDENCE + 10} so it fires before
 * Spring Security and the JWT filter — every log line from the moment a
 * request enters the chain (including security failures) carries the id.
 *
 * <p>The Phase 1.1 logback pattern reserves three MDC slots:
 * {@code [%X{requestId},%X{userId},%X{tenantId}]}. This filter populates
 * {@code requestId}; {@link com.orochiverse.platform.common.security.auth.JwtAuthenticationFilter}
 * populates {@code userId} and {@code tenantId} when it knows them.
 *
 * <p>Honors a client-supplied {@code X-Request-Id} so distributed traces
 * stitched together by an upstream gateway stay coherent. Otherwise we
 * generate a fresh UUID (we use the first 16 hex chars to keep log lines
 * compact while still giving plenty of entropy for one process).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestIdMdcFilter extends OncePerRequestFilter {

    public static final String HEADER_REQUEST_ID = "X-Request-Id";
    public static final String MDC_REQUEST_ID = "requestId";
    public static final String MDC_USER_ID = "userId";
    public static final String MDC_TENANT_ID = "tenantId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = request.getHeader(HEADER_REQUEST_ID);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        MDC.put(MDC_REQUEST_ID, requestId);
        response.setHeader(HEADER_REQUEST_ID, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Clean up the slots we own. JwtAuthenticationFilter clears the
            // ones it owns in its own finally block.
            MDC.remove(MDC_REQUEST_ID);
        }
    }
}
