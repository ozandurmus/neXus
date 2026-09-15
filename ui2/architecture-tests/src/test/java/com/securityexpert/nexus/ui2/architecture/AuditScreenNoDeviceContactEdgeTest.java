package com.securityexpert.nexus.ui2.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

/**
 * `UI2_0_B1_08_AUDIT_LOGS_SCREEN_CONTRACT.md` §8 test 12
 * ({@code AuditScreenNoDeviceContactEdgeTest}), proving §1's boundary
 * mechanically: "this screen reads this product's own PostgreSQL database
 * and nothing else... no code path this movement adds may reach job-engine,
 * worker, scheduler or any vendor adapter."
 *
 * <p>Same {@link ClassFileImporter}/package-infix shape as {@link
 * Ui2ArchitectureTest}'s {@code dir2}/{@code dir3} rules -- a separate class
 * because this rule is screen-scoped, not one of the contract's numbered
 * {@code DIR-n} platform rules.</p>
 */
class AuditScreenNoDeviceContactEdgeTest {

    private static final String AUDIT_SCREEN = "..service.audit..";
    private static final String AUDIT_CONTROLLER = "com.securityexpert.nexus.ui2.service.api.AuditController";
    private static final String JOBS = "..jobs..";
    private static final String WORKER = "..worker..";
    private static final String SCHEDULER = "..scheduler..";
    private static final String LDAP = "..identity.ldap..";

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.securityexpert.nexus.ui2");
        assertFalse(classes.isEmpty(), "ClassFileImporter found no com.securityexpert.nexus.ui2 classes on the test classpath");
        assertTrue(classes.stream().anyMatch(c -> c.getPackageName().contains("service.audit")),
                "no imported class resides in service.audit -- this rule's subject would be vacuous");
    }

    @Test
    void auditProjectionPackageNeverDependsOnADevicePath() {
        ArchRule rule = noClasses().that().resideInAPackage(AUDIT_SCREEN)
                .should().dependOnClassesThat().resideInAnyPackage(JOBS, WORKER, SCHEDULER, LDAP);
        rule.check(classes);
    }

    @Test
    void auditControllerNeverDependsOnADevicePath() {
        ArchRule rule = noClasses().that().haveFullyQualifiedName(AUDIT_CONTROLLER)
                .should().dependOnClassesThat().resideInAnyPackage(JOBS, WORKER, SCHEDULER, LDAP);
        rule.check(classes);
    }
}
