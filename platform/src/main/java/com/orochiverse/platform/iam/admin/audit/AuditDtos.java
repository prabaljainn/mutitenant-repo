package com.orochiverse.platform.iam.admin.audit;

import java.time.Instant;
import java.util.Map;

import com.orochiverse.platform.common.audit.AuditAction;
import com.orochiverse.platform.common.audit.AuditEntry;

public final class AuditDtos {

    private AuditDtos() {}

    public record AuditEntryResponse(
            String id,
            Instant timestamp,
            String actorUserId,
            AuditAction action,
            String tenantId,
            Map<String, Object> metadata) {

        public static AuditEntryResponse from(AuditEntry e) {
            return new AuditEntryResponse(e.id(), e.timestamp(), e.actorUserId(),
                    e.action(), e.tenantId(), e.metadata());
        }
    }
}
