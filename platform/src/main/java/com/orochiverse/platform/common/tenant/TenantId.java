package com.orochiverse.platform.common.tenant;

import java.util.regex.Pattern;

/**
 * Validation rules for tenant identifiers.
 *
 * Tenant IDs become part of MongoDB database names ({@code tenant_<id>_db}),
 * are written into JWT claims, appear in URLs and audit logs, and may be
 * compared via case-sensitive string equality across the codebase. The rules
 * below exist so they can never break any of those contracts.
 *
 * <ul>
 *   <li>Lowercase {@code [a-z0-9]} only, plus {@code _} and {@code -} as
 *       internal separators.</li>
 *   <li>Must start with a letter or digit (no leading separator).</li>
 *   <li>Length 1–50 — keeps the resulting DB name under MongoDB's 64-char
 *       limit ({@code tenant_} + id + {@code _db} = 60 chars max).</li>
 * </ul>
 *
 * Anything outside this rule set rejects with {@link IllegalArgumentException}
 * at the boundary, so internal code can treat tenant IDs as already-safe.
 */
public final class TenantId {

    public static final int MAX_LENGTH = 50;

    private static final Pattern VALID = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,49}$");

    private TenantId() {}

    /**
     * Throws if the supplied value isn't a legal tenant ID.
     *
     * @return the same value, for fluent use at boundaries
     */
    public static String requireValid(String value) {
        if (value == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid tenantId '" + value
                    + "' — must match " + VALID.pattern());
        }
        return value;
    }

    /** Returns the MongoDB database name for a given tenant. */
    public static String dbName(String tenantId) {
        return "tenant_" + requireValid(tenantId) + "_db";
    }
}
