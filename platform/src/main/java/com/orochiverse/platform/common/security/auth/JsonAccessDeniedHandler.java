package com.orochiverse.platform.common.security.auth;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 403 counterpart to {@link JsonAuthenticationEntryPoint} — fires when the
 * caller IS authenticated but lacks the required role / permission for the
 * endpoint they're hitting.
 */
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper mapper;

    public JsonAccessDeniedHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), Map.of(
                "status", 403,
                "error", "forbidden",
                "message", "Insufficient permission for this resource",
                "path", request.getRequestURI(),
                "timestamp", Instant.now().toString()));
    }
}
