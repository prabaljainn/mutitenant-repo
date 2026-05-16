package com.orochiverse.platform.iam.admin.audit;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.orochiverse.platform.common.audit.AuditEntryRepository;
import com.orochiverse.platform.iam.admin.audit.AuditDtos.AuditEntryResponse;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Read-only audit query. Supports paging and one of two filters at a
 * time: by actor (operator user id) or by tenant id.
 *
 * <p>Restricted to {@code OPERATOR_ADMIN} for now. A scoped SUPPORT view
 * (rows where tenantId is visible OR actor is self) is a future follow-up
 * — the {@code $or} Mongo query needed to do this correctly is bigger
 * than the value of an unscoped read.
 *
 * <p>Filtering on both at once would need a third repository method;
 * deferred until a real use case appears. Combinatorial growth in audit
 * filters is the path to nobody using audit at all.
 */
@RestController
@RequestMapping("/admin/api/audit")
@ConditionalOnProperty(prefix = "spring.data.mongodb", name = "uri")
@Tag(name = "Operator: Audit", description = "Read-only audit log query. Page + "
        + "filter by actor or tenant. Restricted to OPERATOR_ADMIN.")
public class AuditAdminController {

    private static final int MAX_PAGE_SIZE = 200;
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "timestamp");

    private final AuditEntryRepository audit;

    public AuditAdminController(AuditEntryRepository audit) {
        this.audit = audit;
    }

    @GetMapping
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public List<AuditEntryResponse> query(
            @RequestParam(required = false) String actorUserId,
            @RequestParam(required = false) String tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        int pageIdx = Math.max(0, page);
        int pageSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        Pageable pg = PageRequest.of(pageIdx, pageSize);
        Pageable pgSorted = PageRequest.of(pageIdx, pageSize, NEWEST_FIRST);

        List<?> rows;
        if (actorUserId != null && !actorUserId.isBlank()) {
            rows = audit.findAllByActorUserIdOrderByTimestampDesc(actorUserId, pg);
        } else if (tenantId != null && !tenantId.isBlank()) {
            rows = audit.findAllByTenantIdOrderByTimestampDesc(tenantId, pg);
        } else {
            // Unfiltered listing — for the small-volume admin console; the
            // TTL index keeps this manageable. We pass an explicit sort so
            // newest rows come first, mirroring the filtered queries.
            rows = audit.findAll(pgSorted).getContent();
        }

        return rows.stream()
                .map(r -> AuditEntryResponse.from((com.orochiverse.platform.common.audit.AuditEntry) r))
                .toList();
    }
}
