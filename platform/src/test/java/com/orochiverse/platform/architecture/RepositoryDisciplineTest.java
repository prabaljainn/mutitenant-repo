package com.orochiverse.platform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Discipline rules that prevent tenant-scoped data accidentally landing in
 * {@code iam_db}.
 *
 * <p>The first rule is the load-bearing one: only the {@code common} module
 * may inject {@code MongoTemplate} or {@code MongoClient} directly. Anything
 * inside {@code tenant} or {@code gcs} that wants to talk to Mongo MUST go
 * through {@link com.orochiverse.platform.common.tenant.TenantMongoTemplateRegistry}.
 *
 * <p>The {@code iam} module is allowed to use {@code MongoTemplate} directly
 * (and Spring Data {@code MongoRepository} interfaces, which use it under the
 * hood) because IAM data legitimately lives in the shared {@code iam_db}.
 *
 * <p>Tightening: if violations creep in, the fix is in code (use the
 * registry). Don't relax these rules without a written justification.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RepositoryDisciplineTest {

    private static final String BASE_PACKAGE = "com.orochiverse.platform";
    private static final String COMMON_PKG = BASE_PACKAGE + ".common..";
    private static final String IAM_PKG = BASE_PACKAGE + ".iam..";
    private static final String TENANT_PKG = BASE_PACKAGE + ".tenant..";
    private static final String GCS_PKG = BASE_PACKAGE + ".gcs..";

    private JavaClasses classes;

    @BeforeAll
    void importProductionClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE_PACKAGE);
    }

    @Test
    void tenant_module_must_not_inject_mongo_template_directly() {
        ArchRule rule = noClasses().that().resideInAPackage(TENANT_PKG)
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.data.mongodb.core.MongoTemplate")
                .because("tenant module must route through TenantMongoTemplateRegistry"
                        + ".forCurrentTenant() so writes land in the active tenant's DB,"
                        + " not iam_db");
        rule.allowEmptyShould(true).check(classes);
    }

    @Test
    void gcs_module_must_not_inject_mongo_template_directly() {
        ArchRule rule = noClasses().that().resideInAPackage(GCS_PKG)
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.data.mongodb.core.MongoTemplate")
                .because("gcs module (M2+) must route through TenantMongoTemplateRegistry"
                        + ".forCurrentTenant() so drone/mission data lands in the active"
                        + " tenant's DB, not iam_db");
        rule.allowEmptyShould(true).check(classes);
    }

    @Test
    void tenant_module_must_not_extend_mongo_repository_directly() {
        ArchRule rule = noClasses().that().resideInAPackage(TENANT_PKG)
                .should().beAssignableTo("org.springframework.data.mongodb.repository.MongoRepository")
                .because("Spring Data MongoRepository interfaces bind to the autoconfigured"
                        + " MongoTemplate (iam_db). Tenant repositories must be hand-rolled"
                        + " components calling registry.forCurrentTenant() per operation.");
        rule.allowEmptyShould(true).check(classes);
    }

    @Test
    void gcs_module_must_not_extend_mongo_repository_directly() {
        ArchRule rule = noClasses().that().resideInAPackage(GCS_PKG)
                .should().beAssignableTo("org.springframework.data.mongodb.repository.MongoRepository")
                .because("Same reasoning as tenant module — MongoRepository auto-wires to iam_db.");
        rule.allowEmptyShould(true).check(classes);
    }

    @Test
    void only_common_may_inject_raw_mongo_client() {
        // MongoClient sits below MongoTemplate in the abstraction stack —
        // even more dangerous to touch from feature modules.
        ArchRule rule = noClasses().that().resideInAnyPackage(IAM_PKG, TENANT_PKG, GCS_PKG)
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("com.mongodb.client.MongoClient")
                .because("Only common.* may construct MongoTemplates directly via MongoClient."
                        + " IAM uses Spring Data repos; tenant/gcs use the registry.");
        rule.allowEmptyShould(true).check(classes);
    }

    @Test
    void mongo_template_fields_must_live_in_common_or_iam_only() {
        // Catch-all in case someone bypasses constructor injection.
        ArchRule rule = fields().that()
                .haveRawType("org.springframework.data.mongodb.core.MongoTemplate")
                .should().beDeclaredInClassesThat().resideInAnyPackage(COMMON_PKG, IAM_PKG)
                .because("MongoTemplate fields anywhere outside common/iam are a"
                        + " tenant-isolation hazard — see TenantMongoTemplateRegistry");
        rule.allowEmptyShould(true).check(classes);
    }
}
