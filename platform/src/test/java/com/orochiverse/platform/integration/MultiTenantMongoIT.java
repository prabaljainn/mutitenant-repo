package com.orochiverse.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.mongodb.client.MongoClient;

import com.orochiverse.platform.common.tenant.MissingTenantContextException;
import com.orochiverse.platform.common.tenant.TenantContext;
import com.orochiverse.platform.common.tenant.TenantDatabaseProvisioner;
import com.orochiverse.platform.common.tenant.TenantId;
import com.orochiverse.platform.common.tenant.TenantMongoTemplateRegistry;
import com.orochiverse.platform.testsupport.MongoTestSupport;

/**
 * End-to-end test of the Phase 1.3 multi-tenant Mongo wiring against a real
 * Mongo 8 replica set (started by {@code ./scripts/dev-up.sh}).
 *
 * Coverage:
 * <ul>
 *   <li><b>Provisioning:</b> dynamically create a brand-new tenant database
 *       and verify it shows up in the cluster's database list.</li>
 *   <li><b>Deprovisioning:</b> drop a tenant database and verify it's gone.</li>
 *   <li><b>Routing:</b> writes inside one tenant context land in that
 *       tenant's DB and not in any other tenant's DB.</li>
 *   <li><b>Fail-loud:</b> calling {@code forCurrentTenant()} without a
 *       bound context throws {@link MissingTenantContextException}.</li>
 *   <li><b>Idempotency:</b> {@code provision} on an already-provisioned
 *       tenant is a no-op; {@code deprovision} on a non-existent tenant
 *       is a no-op.</li>
 *   <li><b>Cache eviction:</b> after deprovision, a fresh
 *       {@code forTenant} returns a clean template against an empty DB.</li>
 * </ul>
 *
 * Tenant IDs are randomised per run so concurrent test executions don't
 * stomp on each other; each test cleans up its own tenants in
 * {@link #afterEach}.
 */
@SpringBootTest
@ActiveProfiles("integration")
@EnabledIf("com.orochiverse.platform.testsupport.MongoTestSupport#mongoIsReachable")
class MultiTenantMongoIT {

    @DynamicPropertySource
    static void mongoProps(DynamicPropertyRegistry registry) {
        MongoTestSupport.mongoProps(registry);
    }

    @Autowired MongoClient mongoClient;
    @Autowired TenantMongoTemplateRegistry registry;
    @Autowired TenantDatabaseProvisioner provisioner;

    private String alphaId;
    private String betaId;

    @BeforeEach
    void allocateRandomTenantIds() {
        // Short random suffix so parallel CI runs don't collide.
        var suffix = UUID.randomUUID().toString().substring(0, 8);
        alphaId = "p13a" + suffix;
        betaId = "p13b" + suffix;
    }

    @AfterEach
    void tearDownTenants() {
        // Always clean up — even if the test failed, we don't want
        // orphan DBs polluting the dev cluster.
        provisioner.deprovision(alphaId);
        provisioner.deprovision(betaId);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Dynamic provisioning
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void can_dynamically_provision_a_new_tenant_database() {
        var dbsBefore = listDatabases();
        assertThat(dbsBefore).doesNotContain(TenantId.dbName(alphaId));

        var dbName = provisioner.provision(alphaId);
        assertThat(dbName).isEqualTo("tenant_" + alphaId + "_db");

        assertThat(listDatabases()).contains(TenantId.dbName(alphaId));

        // The provisioning marker doc is present.
        var marker = mongoClient.getDatabase(dbName)
                .getCollection("_provisioning_marker")
                .find()
                .first();
        assertThat(marker).isNotNull();
        assertThat(marker.getString("tenantId")).isEqualTo(alphaId);
        assertThat(marker.getString("provisionedAt")).isNotBlank();
    }

    @Test
    void provisioning_is_idempotent() {
        provisioner.provision(alphaId);
        var firstMarkerCount = markerCount(alphaId);
        provisioner.provision(alphaId);
        provisioner.provision(alphaId);
        assertThat(markerCount(alphaId))
                .as("second & third provision calls should be no-ops")
                .isEqualTo(firstMarkerCount);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Dynamic deprovisioning
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void can_dynamically_drop_a_tenant_database() {
        provisioner.provision(alphaId);
        assertThat(listDatabases()).contains(TenantId.dbName(alphaId));

        provisioner.deprovision(alphaId);

        assertThat(listDatabases())
                .as("DB should be gone from the cluster after deprovision")
                .doesNotContain(TenantId.dbName(alphaId));
    }

    @Test
    void deprovision_on_unknown_tenant_is_a_noop() {
        var ghostId = "ghost" + UUID.randomUUID().toString().substring(0, 8);
        // Should not throw.
        provisioner.deprovision(ghostId);
        assertThat(listDatabases()).doesNotContain(TenantId.dbName(ghostId));
    }

    @Test
    void after_deprovision_a_subsequent_provision_starts_clean() {
        provisioner.provision(alphaId);
        var template = registry.forTenant(alphaId);
        template.insert(new Document("payload", "first-life"), "stuff");
        assertThat(template.getCollection("stuff").countDocuments()).isEqualTo(1);

        provisioner.deprovision(alphaId);
        provisioner.provision(alphaId);

        // A fresh DB — only the marker exists, the old "stuff" data is gone.
        var fresh = registry.forTenant(alphaId);
        assertThat(fresh.collectionExists("stuff")).isFalse();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Cross-tenant routing & isolation
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void writes_route_to_the_correct_tenant_db_based_on_active_context() {
        provisioner.provision(alphaId);
        provisioner.provision(betaId);

        TenantContext.runIn(alphaId, () ->
                registry.forCurrentTenant().insert(new Document("from", "alpha"), "drones"));
        TenantContext.runIn(betaId, () ->
                registry.forCurrentTenant().insert(new Document("from", "beta"), "drones"));

        var alphaDocs = mongoClient.getDatabase(TenantId.dbName(alphaId))
                .getCollection("drones").find().into(new ArrayList<>());
        var betaDocs = mongoClient.getDatabase(TenantId.dbName(betaId))
                .getCollection("drones").find().into(new ArrayList<>());

        assertThat(alphaDocs).hasSize(1);
        assertThat(alphaDocs.get(0).getString("from")).isEqualTo("alpha");

        assertThat(betaDocs).hasSize(1);
        assertThat(betaDocs.get(0).getString("from")).isEqualTo("beta");
    }

    @Test
    void context_switch_isolates_reads_too() {
        provisioner.provision(alphaId);
        provisioner.provision(betaId);

        TenantContext.runIn(alphaId, () ->
                registry.forCurrentTenant().insert(new Document("only", "in alpha"), "isolated"));

        // From beta's context we must NOT see alpha's doc.
        TenantContext.runIn(betaId, () -> {
            var betaSeesAlphasData = registry.forCurrentTenant()
                    .getCollection("isolated").countDocuments();
            assertThat(betaSeesAlphasData)
                    .as("beta must not see alpha's data — physical DB isolation")
                    .isZero();
        });

        // And reading back inside alpha's context, we still see it.
        TenantContext.runIn(alphaId, () -> {
            var found = registry.forCurrentTenant()
                    .getCollection("isolated").find().first();
            assertThat(found).isNotNull();
            assertThat(found.getString("only")).isEqualTo("in alpha");
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // Fail-loud
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void resolving_a_template_with_no_tenant_context_throws() {
        assertThat(TenantContext.isBound()).isFalse();
        assertThatThrownBy(() -> registry.forCurrentTenant())
                .isInstanceOf(MissingTenantContextException.class);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private List<String> listDatabases() {
        return mongoClient.listDatabaseNames().into(new ArrayList<>());
    }

    private long markerCount(String tenantId) {
        return mongoClient.getDatabase(TenantId.dbName(tenantId))
                .getCollection("_provisioning_marker")
                .countDocuments();
    }
}
