package com.orochiverse.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.orochiverse.platform.common.audit.AuditAction;
import com.orochiverse.platform.common.audit.AuditEntry;
import com.orochiverse.platform.common.audit.AuditEntryRepository;
import com.orochiverse.platform.common.security.principals.OperatorRole;
import com.orochiverse.platform.common.security.principals.TenantRole;
import com.orochiverse.platform.common.security.principals.UserKind;
import com.orochiverse.platform.iam.operators.OperatorAssignment;
import com.orochiverse.platform.iam.operators.OperatorAssignmentRepository;
import com.orochiverse.platform.iam.tenants.Tenant;
import com.orochiverse.platform.iam.tenants.TenantRepository;
import com.orochiverse.platform.iam.tenants.TenantStatus;
import com.orochiverse.platform.iam.users.User;
import com.orochiverse.platform.iam.users.UserRepository;
import com.orochiverse.platform.testsupport.MongoTestSupport;

/**
 * End-to-end exercise of the Phase 1.4 data layer — Spring Data Mongo
 * repositories against the real {@code iam_db} (started by
 * {@code ./scripts/dev-up.sh}), plus verification that the Mongock baseline
 * indexes were applied at startup.
 *
 * <p>Each test scopes its data with a unique random suffix so re-runs and
 * concurrent CI executions don't collide. {@link #afterEach} deletes
 * everything it created.
 */
@SpringBootTest
@ActiveProfiles("integration")
@EnabledIf("com.orochiverse.platform.testsupport.MongoTestSupport#mongoIsReachable")
class IamRepositoriesIT {

    @DynamicPropertySource
    static void mongoProps(DynamicPropertyRegistry registry) {
        MongoTestSupport.mongoProps(registry);
    }

    @Autowired UserRepository users;
    @Autowired TenantRepository tenants;
    @Autowired OperatorAssignmentRepository assignments;
    @Autowired AuditEntryRepository audit;
    @Autowired MongoTemplate iamTemplate;

    private String suffix;
    private String tenantA;
    private String tenantB;
    private String operatorId;
    private String tenantUserId;

    @BeforeEach
    void allocateIds() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        tenantA = "p14a" + suffix;
        tenantB = "p14b" + suffix;
        operatorId = "op-" + suffix;
        tenantUserId = "tu-" + suffix;
    }

    @AfterEach
    void cleanup() {
        // Best-effort cleanup of every doc this test class might create.
        users.deleteById(operatorId);
        users.deleteById(tenantUserId);
        tenants.deleteById(tenantA);
        tenants.deleteById(tenantB);
        assignments.findAllByOperatorUserId(operatorId).forEach(a -> assignments.deleteById(a.id()));
        audit.findAllByActorUserIdOrderByTimestampDesc(operatorId, PageRequest.of(0, 100))
                .forEach(e -> audit.deleteById(e.id()));
    }

    // ─────────────────────────────────────────────────────────────────────
    // Mongock baseline indexes
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void mongock_baseline_indexes_were_created() {
        assertThat(indexNames("users"))
                .contains("uniq_users_email", "idx_users_kind", "idx_users_status",
                        "idx_users_tenant_status");
        assertThat(indexNames("tenants")).contains("idx_tenants_status");
        assertThat(indexNames("operator_assignments"))
                .contains("uniq_opassign_user_tenant", "idx_opassign_tenant");
        assertThat(indexNames("audit_log"))
                .contains("ttl_audit_timestamp", "idx_audit_actor_time", "idx_audit_tenant_time");
    }

    @Test
    void mongock_changeset_was_recorded() {
        var entries = iamTemplate.getDb()
                .getCollection("mongockChangeLog")
                .find(new Document("changeId", "iam-baseline-indexes-001"));
        assertThat(entries.first())
                .as("Mongock should have logged the baseline ChangeUnit execution")
                .isNotNull();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Tenant + User repositories
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void can_save_and_find_a_tenant() {
        var t = Tenant.newTrial(tenantA, "Acme Corp", "STARTER", operatorId);
        tenants.save(t);

        var found = tenants.findById(tenantA).orElseThrow();
        assertThat(found.name()).isEqualTo("Acme Corp");
        assertThat(found.status()).isEqualTo(TenantStatus.TRIAL);
    }

    @Test
    void can_save_an_operator_user_with_no_tenant_id() {
        var u = User.newOperator(operatorId, "op-" + suffix + "@orochi.example",
                "hash", "Op", "User", OperatorRole.OPERATOR_ADMIN);
        users.save(u);

        var found = users.findById(operatorId).orElseThrow();
        assertThat(found.kind()).isEqualTo(UserKind.OPERATOR);
        assertThat(found.operatorRole()).isEqualTo(OperatorRole.OPERATOR_ADMIN);
        assertThat(found.tenantId()).isNull();
    }

    @Test
    void can_save_a_tenant_user_and_find_by_email_case_insensitively() {
        var email = "Bob-" + suffix + "@acme.example";
        var u = User.newTenantUser(tenantUserId, email, "hash",
                "Bob", "Doe", tenantA, TenantRole.TENANT_OWNER);
        users.save(u);

        var lower = users.findByEmailIgnoreCase(email.toLowerCase()).orElseThrow();
        var upper = users.findByEmailIgnoreCase(email.toUpperCase()).orElseThrow();
        assertThat(lower.id()).isEqualTo(tenantUserId);
        assertThat(upper.id()).isEqualTo(tenantUserId);
    }

    @Test
    void users_email_uniqueness_is_enforced_by_the_index() {
        var sharedEmail = "shared-" + suffix + "@example";
        users.save(User.newOperator(operatorId, sharedEmail, "h",
                "A", "B", OperatorRole.OPERATOR_ADMIN));

        var dup = User.newTenantUser(tenantUserId, sharedEmail, "h",
                "C", "D", tenantA, TenantRole.ADMIN);
        // Save MUST fail on the duplicate-key index.
        try {
            users.save(dup);
            // If we get here, uniqueness wasn't enforced.
            assertThat(false).as("expected DuplicateKeyException on duplicate email").isTrue();
        } catch (org.springframework.dao.DuplicateKeyException expected) {
            // pass
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Operator assignments
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void can_grant_and_query_operator_assignments() {
        users.save(User.newOperator(operatorId, "op-" + suffix + "@orochi.example",
                "h", "Op", "User", OperatorRole.OPERATOR_ADMIN));
        tenants.save(Tenant.newTrial(tenantA, "Acme", "STARTER", operatorId));
        tenants.save(Tenant.newTrial(tenantB, "Vega", "STARTER", operatorId));

        assignments.save(OperatorAssignment.grant(operatorId, tenantA, operatorId));
        assignments.save(OperatorAssignment.grant(operatorId, tenantB, operatorId));

        var byOperator = assignments.findAllByOperatorUserId(operatorId);
        assertThat(byOperator).hasSize(2);
        assertThat(byOperator).extracting(OperatorAssignment::tenantId)
                .containsExactlyInAnyOrder(tenantA, tenantB);

        var byTenant = assignments.findAllByTenantId(tenantA);
        assertThat(byTenant).hasSize(1);
        assertThat(byTenant.getFirst().operatorUserId()).isEqualTo(operatorId);
    }

    @Test
    void operator_assignment_uniqueness_is_enforced() {
        users.save(User.newOperator(operatorId, "op2-" + suffix + "@orochi.example",
                "h", "Op", "User", OperatorRole.OPERATOR_ADMIN));
        tenants.save(Tenant.newTrial(tenantA, "Acme", "STARTER", operatorId));

        assignments.save(OperatorAssignment.grant(operatorId, tenantA, operatorId));
        try {
            assignments.save(OperatorAssignment.grant(operatorId, tenantA, operatorId));
            assertThat(false).as("expected DuplicateKeyException on duplicate assignment").isTrue();
        } catch (org.springframework.dao.DuplicateKeyException expected) {
            // pass
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Audit
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void can_write_and_query_audit_entries() {
        audit.save(AuditEntry.of(AuditAction.LOGIN_SUCCESS, operatorId));
        audit.save(AuditEntry.of(AuditAction.TENANT_CREATED, operatorId));

        var entries = audit.findAllByActorUserIdOrderByTimestampDesc(
                operatorId, PageRequest.of(0, 10));
        assertThat(entries).hasSizeGreaterThanOrEqualTo(2);
        assertThat(entries).extracting(AuditEntry::action)
                .contains(AuditAction.LOGIN_SUCCESS, AuditAction.TENANT_CREATED);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helper
    // ─────────────────────────────────────────────────────────────────────

    private java.util.Set<String> indexNames(String collection) {
        var names = new java.util.HashSet<String>();
        iamTemplate.getCollection(collection).listIndexes().forEach(idx ->
                names.add(idx.getString("name")));
        return names;
    }

    private void assertNotNullDocument(Document d) {
        if (d == null) throw new AssertionError("expected non-null Document");
    }
}
