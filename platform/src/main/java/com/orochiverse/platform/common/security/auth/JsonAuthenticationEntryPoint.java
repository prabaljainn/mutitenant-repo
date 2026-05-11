package com.orochiverse.platform.common.security.auth;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Returns a JSON 401 instead of Spring Security's default form-login
 * redirect / Basic-auth challenge. The body is intentionally minimal —
 * clients should not branch on parser-level reasons, and operators see
 * full detail in the server log.
 */
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper mapper;

    public JsonAuthenticationEntryPoint(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), Map.of(
                "status", 401,
                "error", "unauthorized",
                "message", "Bearer token missing or invalid",
                "path", request.getRequestURI(),
                "timestamp", Instant.now().toString()));
    }
}
