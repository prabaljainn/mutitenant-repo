package com.orochiverse.platform.common.audit;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One audit-log row in {@code iam_db.audit_log}.
 *
 * <p>Audit entries are append-only and aged out automatically by a TTL
 * index on {@code timestamp} (created in Phase 1.4 Mongock; default 365
 * days). For longer-term forensic storage, an exporter job — out of scope
 * for M1 — copies entries to cold storage before they expire.
 *
 * <p>Audit lives in {@code common} (not {@code iam}) because both the IAM
 * module and tenant-admin module write to it. The collection itself sits
 * in {@code iam_db} because audit semantics are platform-wide, not per-
 * tenant. A {@code tenantId} is recorded on each entry so tenant-scoped
 * audit views can filter cheaply.
 *
 * @param id          auto-generated.
 * @param timestamp   wall-clock time of the action.
 * @param actorUserId who performed the action; null for system actions.
 * @param action      one of the enumerated {@link AuditAction}s.
 * @param targetType  free-form noun describing what was acted on (e.g.
 *                    {@code "TENANT"}, {@code "USER"}, {@code "OPERATOR_ASSIGNMENT"}).
 * @param targetId    identifier of the target.
 * @param tenantId    tenant context, if applicable.
 * @param metadata    additional structured details.
 * @param ip          source IP, if available.
 * @param userAgent   user-agent string, if available.
 */
@Document(collection = "audit_log")
public record AuditEntry(
        @Id String id,
        Instant timestamp,
        String actorUserId,
        AuditAction action,
        String targetType,
        String targetId,
        String tenantId,
        Map<String, Object> metadata,
        String ip,
        String userAgent) {

    public AuditEntry {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(action, "action");
    }

    /** Builder-style helper for the common case (most fields optional). */
    public static AuditEntry of(AuditAction action, String actorUserId) {
        return new AuditEntry(null, Instant.now(), actorUserId, action, null, null, null,
                Map.of(), null, null);
    }

    /** Variant for callers that need to attach structured metadata. */
    public static AuditEntry of(AuditAction action, String actorUserId, Map<String, Object> metadata) {
        return new AuditEntry(null, Instant.now(), actorUserId, action, null, null, null,
                Map.copyOf(metadata), null, null);
    }
}
