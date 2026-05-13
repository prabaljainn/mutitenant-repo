package com.orochiverse.platform.iam.admin.common;

/**
 * Typed exceptions used across the {@code /admin/api/*} surface. Grouped
 * in one file because each is a one-line marker — splitting them would
 * obscure the small fixed set of failure modes the admin handlers cover.
 *
 * <p>All map to JSON responses via {@link AdminExceptionHandler}.
 */
public final class AdminExceptions {

    private AdminExceptions() {}

    /** Resource by id was not found in {@code iam_db}. → 404. */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) { super(message); }
    }

    /** Entity already exists or violates a unique constraint. → 409. */
    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) { super(message); }
    }

    /** Caller tried to do something the business rules don't allow. → 422. */
    public static class UnprocessableException extends RuntimeException {
        public UnprocessableException(String message) { super(message); }
    }
}
