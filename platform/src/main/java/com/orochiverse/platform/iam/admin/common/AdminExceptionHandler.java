package com.orochiverse.platform.iam.admin.common;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.orochiverse.platform.iam.admin.common.AdminExceptions.ConflictException;
import com.orochiverse.platform.iam.admin.common.AdminExceptions.NotFoundException;
import com.orochiverse.platform.iam.admin.common.AdminExceptions.UnprocessableException;

/**
 * Maps the typed exceptions thrown by the {@code /admin/api/*} services
 * to JSON responses matching the
 * {@link com.orochiverse.platform.common.security.auth.JsonAuthenticationEntryPoint}
 * envelope shape.
 *
 * <p>Scoped to the admin package so the catch-all 500 handler (Phase 1.10)
 * doesn't override these and so {@link IllegalArgumentException}s coming
 * from {@link com.orochiverse.platform.common.tenant.TenantId#requireValid}
 * land here as 400s instead of as a generic 500.
 */
@RestControllerAdvice(basePackages = {
        "com.orochiverse.platform.iam.admin",
        "com.orochiverse.platform.iam.tenantadmin",
        "com.orochiverse.platform.iam.settings"})
@ConditionalOnProperty(prefix = "spring.data.mongodb", name = "uri")
public class AdminExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NotFoundException e, HttpServletRequest req) {
        return error(HttpStatus.NOT_FOUND, "not_found", e.getMessage(), req);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(ConflictException e, HttpServletRequest req) {
        return error(HttpStatus.CONFLICT, "conflict", e.getMessage(), req);
    }

    @ExceptionHandler(UnprocessableException.class)
    public ResponseEntity<Map<String, Object>> unprocessable(UnprocessableException e,
                                                             HttpServletRequest req) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "unprocessable", e.getMessage(), req);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Map<String, Object>> duplicate(DuplicateKeyException e, HttpServletRequest req) {
        // Last-line defense — services should pre-check and throw
        // ConflictException themselves; this handles races.
        return error(HttpStatus.CONFLICT, "conflict",
                "duplicate key — resource already exists", req);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> illegalArg(IllegalArgumentException e,
                                                          HttpServletRequest req) {
        return error(HttpStatus.BAD_REQUEST, "bad_request", e.getMessage(), req);
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
