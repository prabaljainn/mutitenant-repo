package com.orochiverse.platform.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces the modular-monolith package boundaries declared in the M1 spec:
 *
 *   common/  — shared infrastructure; depended on by everyone
 *   iam/     — operator-facing; must not depend on tenant or gcs
 *   tenant/  — tenant-admin self-service; must not depend on iam or gcs
 *   gcs/     — placeholder for M2+; nothing may depend on it yet
 *
 * If any of these tests fail, the modular boundary has been violated and
 * should be fixed in code rather than relaxed here. Plain JUnit 5 is used
 * (instead of ArchUnit's @AnalyzeClasses/@ArchTest engine) so each rule
 * appears as an individual test method in build reports.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PackageBoundaryTest {

    private static final String BASE_PACKAGE = "com.orochiverse.platform";
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
        ArchRule rule = noClasses().that().resideInAPackage("..iam..")
            .should().dependOnClassesThat().resideInAPackage("..tenant..")
            .because("iam (operator-side) and tenant (tenant-admin self-service) "
                  + "are sibling modules and must not be coupled");
        rule.check(classes);
    }

    @Test
    void tenant_must_not_depend_on_iam() {
        ArchRule rule = noClasses().that().resideInAPackage("..tenant..")
            .should().dependOnClassesThat().resideInAPackage("..iam..")
            .because("iam (operator-side) and tenant (tenant-admin self-service) "
                  + "are sibling modules and must not be coupled");
        rule.check(classes);
    }

    @Test
    void iam_must_not_depend_on_gcs() {
        ArchRule rule = noClasses().that().resideInAPackage("..iam..")
            .should().dependOnClassesThat().resideInAPackage("..gcs..")
            .because("gcs is reserved for M2+ and may not be referenced from iam");
        rule.check(classes);
    }

    @Test
    void tenant_must_not_depend_on_gcs() {
        ArchRule rule = noClasses().that().resideInAPackage("..tenant..")
            .should().dependOnClassesThat().resideInAPackage("..gcs..")
            .because("gcs is reserved for M2+ and may not be referenced from tenant");
        rule.check(classes);
    }

    @Test
    void common_must_not_depend_on_other_modules() {
        ArchRule rule = noClasses().that().resideInAPackage("..common..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..iam..", "..tenant..", "..gcs..")
            .because("common is shared infrastructure and must remain "
                  + "independent of feature modules");
        rule.check(classes);
    }
}
