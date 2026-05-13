package com.orochiverse.platform.common.migrations.iam;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

/**
 * Baseline indexes for the {@code iam_db} collections.
 *
 * <p>Mongock tracks which ChangeUnits have run in {@code mongockChangeLog},
 * so this is safe to ship and never re-runs once applied. To evolve the
 * schema, add new {@link ChangeUnit} classes — never edit this one.
 *
 * <h3>Index inventory</h3>
 * <ul>
 *   <li><b>users</b>: unique on {@code email}; supporting indexes on
 *       {@code kind}, {@code status}, and {@code (tenantId, status)}.</li>
 *   <li><b>tenants</b>: unique already implicit on {@code _id} (slug);
 *       supporting index on {@code status}.</li>
 *   <li><b>operator_assignments</b>: unique compound on
 *       {@code (operatorUserId, tenantId)}; supporting indexes on each
 *       side for fan-out queries.</li>
 *   <li><b>audit_log</b>: TTL on {@code timestamp} (365 days); supporting
 *       indexes for {@code (actorUserId, timestamp)} and
 *       {@code (tenantId, timestamp)}.</li>
 * </ul>
 */
@ChangeUnit(id = "iam-baseline-indexes-001", order = "001", author = "platform")
public class IamBaselineIndexes {

    private static final Logger log = LoggerFactory.getLogger(IamBaselineIndexes.class);
    private static final long AUDIT_TTL_SECONDS = 365L * 24 * 60 * 60;

    @Execution
    public void migrate(MongoTemplate template) {
        log.info("Applying iam_db baseline indexes…");

        // ── users ─────────────────────────────────────────────────────────
        template.indexOps("users").createIndex(
                new Index().on("email", Sort.Direction.ASC)
                        .unique()
                        .named("uniq_users_email")
                        .collation(caseInsensitive()));
        template.indexOps("users").createIndex(
                new Index().on("kind", Sort.Direction.ASC).named("idx_users_kind"));
        template.indexOps("users").createIndex(
                new Index().on("status", Sort.Direction.ASC).named("idx_users_status"));
        template.indexOps("users").createIndex(
                new Index().on("tenantId", Sort.Direction.ASC)
                        .on("status", Sort.Direction.ASC)
                        .named("idx_users_tenant_status"));

        // ── tenants ───────────────────────────────────────────────────────
        // _id is already unique (it IS the slug). Just status for filtering.
        template.indexOps("tenants").createIndex(
                new Index().on("status", Sort.Direction.ASC).named("idx_tenants_status"));

        // ── operator_assignments ──────────────────────────────────────────
        template.indexOps("operator_assignments").createIndex(
                new Index().on("operatorUserId", Sort.Direction.ASC)
                        .on("tenantId", Sort.Direction.ASC)
                        .unique()
                        .named("uniq_opassign_user_tenant"));
        template.indexOps("operator_assignments").createIndex(
                new Index().on("tenantId", Sort.Direction.ASC).named("idx_opassign_tenant"));

        // ── audit_log ────────────────────────────────────────────────────
        template.indexOps("audit_log").createIndex(
                new Index().on("timestamp", Sort.Direction.DESC)
                        .named("ttl_audit_timestamp")
                        .expire(AUDIT_TTL_SECONDS, TimeUnit.SECONDS));
        template.indexOps("audit_log").createIndex(
                new Index().on("actorUserId", Sort.Direction.ASC)
                        .on("timestamp", Sort.Direction.DESC)
                        .named("idx_audit_actor_time"));
        template.indexOps("audit_log").createIndex(
                new Index().on("tenantId", Sort.Direction.ASC)
                        .on("timestamp", Sort.Direction.DESC)
                        .named("idx_audit_tenant_time"));

        log.info("iam_db baseline indexes applied.");
    }

    @RollbackExecution
    public void rollback(MongoTemplate template) {
        log.warn("Rolling back iam_db baseline indexes — this is destructive.");
        for (var coll : new String[] {"users", "tenants", "operator_assignments", "audit_log"}) {
            template.indexOps(coll).dropAllIndexes();
        }
    }

    private static org.springframework.data.mongodb.core.query.Collation caseInsensitive() {
        return org.springframework.data.mongodb.core.query.Collation.of("en")
                .strength(org.springframework.data.mongodb.core.query.Collation.ComparisonLevel.secondary());
    }
}
