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

    List<AuditEntry> findAllByActionAndTimestampAfterOrderByTimestampDesc(
            AuditAction action, Instant after, Pageable page);
}
