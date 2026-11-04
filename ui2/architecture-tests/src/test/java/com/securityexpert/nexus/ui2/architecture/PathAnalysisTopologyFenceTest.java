package com.securityexpert.nexus.ui2.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Path analysis stage A3 (PATH_ANALYSIS_DESIGN.md §7): the topology is a service-side computation over stored
 * inventory. Its package never reaches a device (worker, transport), never admits a job, and never evaluates policy
 * (the per-hop policy/NAT evaluator is stage A2's, built in parallel). Stated as a fence so a later change that wires
 * any of those in fails here.
 */
class PathAnalysisTopologyFenceTest {

    private static final String TOPOLOGY = "com.securityexpert.nexus.ui2.service.pathanalysis.topology..";

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.securityexpert.nexus.ui2");

    @Test
    void theTopologyNeverContactsADeviceAdmitsAJobOrEvaluatesPolicy() {
        assertTrue(classes.stream().anyMatch(c -> c.getPackageName().startsWith(TOPOLOGY.replace("..", ""))),
                "vacuous: no topology class imported");
        noClasses().that().resideInAPackage(TOPOLOGY)
                .should().dependOnClassesThat().resideInAnyPackage("..ui2.worker..", "..ui2.jobs..", "..transport..",
                        "..ui2.service.policy..", "..ui2.policy..", "..ui2.capability..")
                .because("A3 builds from stored inventory only; no device contact, no job, no policy evaluation")
                .check(classes);
    }
}
