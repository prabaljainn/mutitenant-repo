package com.orochiverse.platform.iam.auth;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the typed exceptions thrown by {@link AuthService} to JSON responses
 * matching the shape that {@link com.orochiverse.platform.common.security.auth.JsonAuthenticationEntryPoint}
 * uses, so clients see one consistent error envelope across the auth
 * surface.
 *
 * <p>Scoped to the {@code iam.auth} package so the catch-all 500 handler
 * (Phase 1.10) doesn't override these.
 */
@RestControllerAdvice(basePackages = "com.orochiverse.platform.iam.auth")
@ConditionalOnProperty(prefix = "spring.data.mongodb", name = "uri")
public class AuthExceptionHandler {

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<Map<String, Object>> invalidCredentials(InvalidCredentialsException e,
                                                                  HttpServletRequest req) {
        return error(HttpStatus.UNAUTHORIZED, "invalid_credentials",
                "Invalid email or password", req);
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<Map<String, Object>> invalidRefresh(InvalidRefreshTokenException e,
                                                              HttpServletRequest req) {
        return error(HttpStatus.UNAUTHORIZED, "invalid_refresh_token",
                "Refresh token is invalid or expired", req);
    }

    @ExceptionHandler(OperatorNotAssignedException.class)
    public ResponseEntity<Map<String, Object>> notAssigned(OperatorNotAssignedException e,
                                                           HttpServletRequest req) {
        return error(HttpStatus.FORBIDDEN, "operator_not_assigned",
                "Operator is not assigned to this tenant", req);
    }

    /**
     * Single-use token (invite or password reset) was unknown, expired,
     * already consumed, or had the wrong purpose. One generic message so
     * the response can't distinguish those cases from each other —
     * needed for security parity with login.
     */
    @ExceptionHandler(com.orochiverse.platform.iam.tokens.InvalidTokenException.class)
    public ResponseEntity<Map<String, Object>> invalidToken(
            com.orochiverse.platform.iam.tokens.InvalidTokenException e, HttpServletRequest req) {
        return error(HttpStatus.UNAUTHORIZED, "invalid_token",
                "Token is invalid or expired", req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException e,
                                                          HttpServletRequest req) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return error(HttpStatus.BAD_REQUEST, "validation_failed", detail, req);
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String error,
                                                      String message, HttpServletRequest req) {
        var body = new LinkedHashMap<String, Object>();
        body.put("status", status.value());
        body.put("error", error);
        body.put("message", message);
        body.put("path", req.getRequestURI());
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(status).body(body);
    }
}
