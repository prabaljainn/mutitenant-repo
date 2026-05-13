package com.orochiverse.platform.common.tenant;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;

import com.mongodb.client.MongoClient;

/**
 * Resolves a {@link MongoTemplate} for the active tenant (or an explicit one)
 * by routing to the appropriate per-tenant MongoDB database.
 *
 * <h2>Single client, many templates</h2>
 * One {@link MongoClient} multiplexes all DB access — the connection pool
 * lives on the cluster, not per-DB. Each unique DB name gets exactly one
 * {@code MongoTemplate} instance, cached for the lifetime of the registry.
 * For 5–50 tenants this scales fine; if we ever push past a few hundred
 * tenants the cache should be bounded with an LRU eviction.
 *
 * <h2>Fail-loud</h2>
 * {@link #forCurrentTenant()} throws {@link MissingTenantContextException}
 * when called outside a {@link TenantContext} scope. We deliberately do
 * <em>not</em> silently fall back to the IAM template — that would risk
 * leaking tenant queries into shared data.
 *
 * <h2>Lazy and explicit</h2>
 * Templates are created on first request. MongoDB itself creates the
 * underlying database on first write — but tenant DB <em>provisioning</em>
 * goes through {@link TenantDatabaseProvisioner#provision(String)}, never
 * implicitly via this class. That keeps a typo in a JWT claim from
 * spawning empty databases.
 */
public final class TenantMongoTemplateRegistry {

    private final MongoClient client;
    private final Map<String, MongoTemplate> cache = new ConcurrentHashMap<>();

    public TenantMongoTemplateRegistry(MongoClient client) {
        this.client = client;
    }

    /** Template for the tenant currently bound on the thread's {@link TenantContext}. */
    public MongoTemplate forCurrentTenant() {
        return forTenant(TenantContext.requireCurrent());
    }

    /** Template for an explicitly named tenant (used by admin / cross-tenant flows). */
    public MongoTemplate forTenant(String tenantId) {
        var dbName = TenantId.dbName(tenantId);
        return cache.computeIfAbsent(dbName, name ->
                new MongoTemplate(new SimpleMongoClientDatabaseFactory(client, name)));
    }

    /**
     * Drops the cached template for a tenant. Called by
     * {@link TenantDatabaseProvisioner#deprovision} after the underlying DB
     * is dropped, so a future {@link #forTenant} call rebuilds cleanly
     * against an empty database (rather than serving stale metadata).
     */
    void evictTenant(String tenantId) {
        cache.remove(TenantId.dbName(tenantId));
    }

    /** Test/debug only — never assert this in production code. */
    int cachedTemplateCount() {
        return cache.size();
    }
}
