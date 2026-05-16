package com.orochiverse.platform.common.audit;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import com.orochiverse.platform.common.data.IamScoped;

/**
 * Append-only audit log. Entries expire automatically via the TTL index
 * created in Phase 1.4 Mongock (default 365 days).
 */
@Repository
@IamScoped
public interface AuditEntryRepository extends MongoRepository<AuditEntry, String> {

    List<AuditEntry> findAllByActorUserIdOrderByTimestampDesc(String actorUserId, Pageable page);

    List<AuditEntry> findAllByTenantIdOrderByTimestampDesc(String tenantId, Pageable page);

    List<AuditEntry> findAllByActionOrderByTimestampDesc(AuditAction action, Pageable page);

    List<AuditEntry> findAllByActionAndTimestampAfterOrderByTimestampDesc(
            AuditAction action, Instant after, Pageable page);

    // ─── Date-range variants ──────────────────────────────────────────
    // The controller always passes a since/until pair (padding open-ended
    // bounds with EPOCH/far-future sentinels), so we only need one Between
    // method per categorical filter.

    List<AuditEntry> findAllByTimestampBetweenOrderByTimestampDesc(
            Instant from, Instant to, Pageable page);

    List<AuditEntry> findAllByActorUserIdAndTimestampBetweenOrderByTimestampDesc(
            String actorUserId, Instant from, Instant to, Pageable page);

    List<AuditEntry> findAllByTenantIdAndTimestampBetweenOrderByTimestampDesc(
            String tenantId, Instant from, Instant to, Pageable page);

    List<AuditEntry> findAllByActionAndTimestampBetweenOrderByTimestampDesc(
            AuditAction action, Instant from, Instant to, Pageable page);
}
