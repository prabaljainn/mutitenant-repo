package com.orochiverse.platform.common.migrations.iam;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

/**
 * Adds a compound index on {@code audit_log} for the action-filtered
 * audit-page queries that the baseline didn't cover.
 *
 * <h3>Why</h3>
 * The audit admin page lets operators filter by {@link
 * com.orochiverse.platform.common.audit.AuditAction} alone (e.g.
 * "show me every LOGIN_FAILURE in the last 24h"). Three repository
 * methods walk this path, all sorted by timestamp descending:
 *
 * <ul>
 *   <li>{@code findAllByActionOrderByTimestampDesc(action, page)}</li>
 *   <li>{@code findAllByActionAndTimestampAfterOrderByTimestampDesc(action, since, page)}</li>
 *   <li>{@code findAllByActionAndTimestampBetweenOrderByTimestampDesc(action, from, to, page)}</li>
 * </ul>
 *
 * <p>The baseline only indexed {@code (actorUserId, timestamp)} and
 * {@code (tenantId, timestamp)} — neither helps when {@code action}
 * is the only filter, so every page load full-scanned the collection
 * and then sorted in memory. With this index, the query plan walks
 * the action prefix and returns rows already sorted by timestamp.
 *
 * <h3>Why not extend the baseline</h3>
 * Mongock change units are immutable once applied — editing the
 * baseline would re-run nowhere because the changelog says it's done.
 * New behaviour gets a new ChangeUnit, period.
 */
@ChangeUnit(id = "iam-audit-action-index-002", order = "002", author = "platform")
public class AuditActionIndex {

    private static final Logger log = LoggerFactory.getLogger(AuditActionIndex.class);

    @Execution
    public void migrate(MongoTemplate template) {
        log.info("Adding idx_audit_action_time to audit_log…");
        template.indexOps("audit_log").createIndex(
                new Index().on("action", Sort.Direction.ASC)
                        .on("timestamp", Sort.Direction.DESC)
                        .named("idx_audit_action_time"));
        log.info("idx_audit_action_time applied.");
    }

    @RollbackExecution
    public void rollback(MongoTemplate template) {
        log.warn("Dropping idx_audit_action_time from audit_log.");
        template.indexOps("audit_log").dropIndex("idx_audit_action_time");
    }
}
