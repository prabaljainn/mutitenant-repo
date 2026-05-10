package com.orochiverse.platform.common.tenant;

import java.time.Instant;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mongodb.client.MongoClient;

/**
 * Creates and destroys per-tenant MongoDB databases.
 *
 * <h2>Provisioning</h2>
 * MongoDB lazy-creates a database on first write, so {@link #provision} writes
 * a sentinel document into a {@code _provisioning_marker} collection. Two
 * benefits:
 * <ol>
 *   <li>The DB shows up in {@code listDatabases} immediately, so tooling and
 *       monitoring can see it.</li>
 *   <li>The marker records who provisioned the tenant and when, useful for
 *       debugging and audit reconstruction.</li>
 * </ol>
 * Idempotent — calling {@code provision} on an existing tenant DB is a no-op.
 *
 * <p>Phase 1.4 will extend this to also run Mongock changesets that create
 * the per-tenant index set. M1 keeps the contract minimal so tests and
 * tenant-onboarding code can lean on it now.
 *
 * <h2>Deprovisioning</h2>
 * {@link #deprovision} drops the tenant database outright. There is no
 * soft-delete here — soft-deletion belongs to the {@code tenants}
 * collection in {@code iam_db}; the operational database itself is
 * either present or it isn't. Caller code (tenant lifecycle service) is
 * responsible for ensuring the tenant is marked {@code ARCHIVED} before
 * calling this.
 */
public final class TenantDatabaseProvisioner {

    private static final Logger log = LoggerFactory.getLogger(TenantDatabaseProvisioner.class);
    private static final String MARKER_COLLECTION = "_provisioning_marker";

    private final MongoClient client;
    private final TenantMongoTemplateRegistry registry;

    public TenantDatabaseProvisioner(MongoClient client, TenantMongoTemplateRegistry registry) {
        this.client = client;
        this.registry = registry;
    }

    /** Creates the tenant database (idempotent). Returns the DB name. */
    public String provision(String tenantId) {
        var dbName = TenantId.dbName(tenantId);
        var template = registry.forTenant(tenantId);

        if (template.collectionExists(MARKER_COLLECTION)) {
            log.debug("Tenant database {} already provisioned — no-op", dbName);
            return dbName;
        }

        template.createCollection(MARKER_COLLECTION);
        template.insert(
                new Document("tenantId", tenantId).append("provisionedAt", Instant.now().toString()),
                MARKER_COLLECTION);
        log.info("Provisioned tenant database {}", dbName);
        return dbName;
    }

    /**
     * Drops the tenant database. Idempotent — dropping a non-existent DB is
     * a no-op as far as MongoDB is concerned.
     */
    public void deprovision(String tenantId) {
        var dbName = TenantId.dbName(tenantId);
        client.getDatabase(dbName).drop();
        registry.evictTenant(tenantId);
        log.info("Deprovisioned tenant database {}", dbName);
    }
}
