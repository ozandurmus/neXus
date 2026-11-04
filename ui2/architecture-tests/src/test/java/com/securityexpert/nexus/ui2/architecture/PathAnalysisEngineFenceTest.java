package com.securityexpert.nexus.ui2.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Path analysis stage A4 (PATH_ANALYSIS_DESIGN.md §5, §7; PO record 2026-11-03 D-2): the path engine is a pure,
 * in-memory computation over an A3 topology graph and the A2 hop rule engine. It never reaches a device, never admits a
 * job, and never reads or writes storage -- path results are not persisted (D-2), and inventory/policy are handed to it.
 * Only the inventory row records (the route type of the graph) may be referenced from the persistence layer.
 */
class PathAnalysisEngineFenceTest {

    private static final String ENGINE = "com.securityexpert.nexus.ui2.service.pathanalysis.engine..";

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.securityexpert.nexus.ui2");

    @Test
    void theEngineNeverContactsADeviceAdmitsAJobOrTouchesStorage() {
        assertTrue(classes.stream().anyMatch(c -> c.getPackageName().startsWith(ENGINE.replace("..", ""))),
                "vacuous: no engine class imported");
        noClasses().that().resideInAPackage(ENGINE)
                .should().dependOnClassesThat().resideInAnyPackage("..ui2.worker..", "..ui2.jobs..", "..transport..",
                        "..ui2.capability..", "org.jooq..", "org.springframework.jdbc..", "java.sql..")
                .because("A4 evaluates in memory: no device contact, no job, no storage (D-2)")
                .check(classes);
        noClasses().that().resideInAPackage(ENGINE)
                .should().dependOnClassesThat(resideInAPackage("..ui2.persistence..")
                        .and(not(resideInAPackage("..ui2.persistence.device.inventory.."))))
                .because("only the inventory row records may be referenced; policy and bindings are handed in")
                .check(classes);
        noClasses().that().resideInAPackage(ENGINE)
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
                .because("path results are never persisted and the engine reads nothing itself (D-2)")
                .check(classes);
    }
}
