package com.orochiverse.platform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Enforces the modular-monolith package boundaries declared in the M1 spec:
 *
 *   common/  — shared infrastructure; depended on by everyone
 *   iam/     — operator-facing; must not depend on tenant or gcs
 *   tenant/  — tenant-admin self-service; must not depend on iam or gcs
 *   gcs/     — placeholder for M2+; nothing may depend on it yet
 *
 * Note: each module is matched by its FULL package path (e.g.
 * {@code com.orochiverse.platform.tenant..}) — not the loose
 * {@code ..tenant..} glob — so internal sub-packages whose names happen to
 * include another module's name (like {@code common.tenant} for tenant
 * context plumbing) don't get flagged as cross-module dependencies.
 *
 * If any of these tests fail, the modular boundary has been violated and
 * should be fixed in code rather than relaxed here.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PackageBoundaryTest {

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
        if (classes.isEmpty()) {
            throw new IllegalStateException(
                    "ArchUnit imported zero classes from " + BASE_PACKAGE
                            + " — package layout or build output is wrong");
        }
    }

    @Test
    void iam_must_not_depend_on_tenant() {
        ArchRule rule = noClasses().that().resideInAPackage(IAM_PKG)
                .should().dependOnClassesThat().resideInAPackage(TENANT_PKG)
                .because("iam (operator-side) and tenant (tenant-admin self-service) "
                        + "are sibling modules and must not be coupled");
        rule.check(classes);
    }

    @Test
    void tenant_must_not_depend_on_iam() {
        ArchRule rule = noClasses().that().resideInAPackage(TENANT_PKG)
                .should().dependOnClassesThat().resideInAPackage(IAM_PKG)
                .because("iam (operator-side) and tenant (tenant-admin self-service) "
                        + "are sibling modules and must not be coupled");
        rule.check(classes);
    }

    @Test
    void iam_must_not_depend_on_gcs() {
        ArchRule rule = noClasses().that().resideInAPackage(IAM_PKG)
                .should().dependOnClassesThat().resideInAPackage(GCS_PKG)
                .because("gcs is reserved for M2+ and may not be referenced from iam");
        rule.check(classes);
    }

    @Test
    void tenant_must_not_depend_on_gcs() {
        ArchRule rule = noClasses().that().resideInAPackage(TENANT_PKG)
                .should().dependOnClassesThat().resideInAPackage(GCS_PKG)
                .because("gcs is reserved for M2+ and may not be referenced from tenant");
        rule.check(classes);
    }

    @Test
    void common_must_not_depend_on_other_modules() {
        ArchRule rule = noClasses().that().resideInAPackage(COMMON_PKG)
                .should().dependOnClassesThat()
                .resideInAnyPackage(IAM_PKG, TENANT_PKG, GCS_PKG)
                .because("common is shared infrastructure and must remain "
                        + "independent of feature modules");
        rule.check(classes);
    }
}
