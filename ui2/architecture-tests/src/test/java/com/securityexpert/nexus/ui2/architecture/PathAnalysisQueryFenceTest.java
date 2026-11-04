package com.securityexpert.nexus.ui2.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Path analysis stage A5a (PATH_ANALYSIS_DESIGN.md §7; PO record 2026-11-03 D-2, D-3, D-8): the path query is a read.
 * Its package and its controller never reach a device or admit a job, never build the topology (a query that finds no
 * held topology answers 409 TOPOLOGY_NOT_BUILT; a build is topology_rebuild, security_admin only), never write a policy
 * collection, a binding or a pseudonym claim, and never use the reverse-resolving cluster lookup D-3 rules out.
 */
class PathAnalysisQueryFenceTest {

    private static final String QUERY = "com.securityexpert.nexus.ui2.service.pathanalysis.query..";
    private static final String CONTROLLER = "com.securityexpert.nexus.ui2.service.api.PathAnalysisController";

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.securityexpert.nexus.ui2");

    @Test
    void aPathQueryNeverBuildsCollectsOrWrites() {
        assertTrue(classes.stream().anyMatch(c -> c.getPackageName().startsWith(QUERY.replace("..", ""))),
                "vacuous: no query class imported");
        assertTrue(classes.stream().anyMatch(c -> c.getName().equals(CONTROLLER)), "vacuous: no controller imported");
        noClasses().that().resideInAPackage(QUERY).or().haveFullyQualifiedName(CONTROLLER)
                .should().dependOnClassesThat().resideInAnyPackage("..ui2.worker..", "..ui2.jobs..", "..transport..",
                        "..ui2.capability..", "org.jooq..", "java.sql..")
                .because("A5a reads stored inventory and policy only: no device contact, no job (D-2, D-3 condition 4)")
                .check(classes);
        noClasses().that().resideInAPackage(QUERY).or().haveFullyQualifiedName(CONTROLLER)
                .should().callMethodWhere(com.tngtech.archunit.core.domain.JavaCall.Predicates.target(
                        com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching(
                                "rebuild|createOverride|revokeOverride|saveTargetBindings|recordSnapshot|resolveClusterName")))
                .because("a query never builds the topology, writes an override or binding, or resolves a cluster "
                        + "pseudonym without the D-3 ambiguity check")
                .check(classes);
    }
}
