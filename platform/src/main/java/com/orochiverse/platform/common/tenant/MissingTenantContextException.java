package com.orochiverse.platform.common.tenant;

/**
 * Thrown when code that requires an active tenant context runs outside one.
 *
 * This is intentionally a runtime exception so callers can't accidentally
 * swallow it. It signals a programming error: either the caller forgot to
 * wrap their work in {@link TenantContext#runIn} / {@link TenantContext#callIn},
 * or a tenant-scoped repository/service was invoked from an admin-only path
 * that should be using {@code MongoTemplate} (the IAM template) directly.
 */
public final class MissingTenantContextException extends RuntimeException {

    public MissingTenantContextException() {
        super("No tenant context bound. Tenant-scoped operations must run inside "
                + "TenantContext.runIn(...) or TenantContext.callIn(...).");
    }
}
