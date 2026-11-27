package com.securityexpert.nexus.ui2.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;

/**
 * Architectural isolation fences for {@code ..eventintake..} ({@code ui2-event-intake} pod) and
 * {@code ..eventtrigger..} ({@code PO_DECISION_RECORD_2026_11_26_POLICY_CHANGE_EVENT_TRIGGER.md}
 * {@code Q8} §1.1 and §2 "Non-Scope and Unchanged Boundaries").
 */
class EventIntakeModuleFenceTest {

    private static final String EVENT_INTAKE = "..eventintake..";
    private static final String EVENT_TRIGGER = "..eventtrigger..";

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = Ui2ArchitectureTest.productionClasses();
        assertFalse(classes.isEmpty(),
                "ClassFileImporter found no com.securityexpert.nexus.ui2 classes on the test classpath");
        assertPackageNonEmpty(EVENT_INTAKE);
        assertPackageNonEmpty(EVENT_TRIGGER);
    }

    private static void assertPackageNonEmpty(String packageInfix) {
        String token = packageInfix.replace("..", "");
        long count = classes.stream()
                .filter(c -> c.getPackageName().contains(token))
                .count();
        assertTrue(count > 0, "no imported class resides in a package matching " + packageInfix
                + " — this rule's subject would be vacuous");
    }

    /**
     * Q8 (§1.1) and §2: {@code ..eventintake..} and {@code ..eventtrigger..} have zero device-network
     * egress, zero device logging writes, and never depend on worker modules, device SSH/HTTPS/SNMP/XML-API
     * transports, or vendor collector packages.
     */
    @Test
    void eventIntakeAndEventTriggerNeverDependOnWorkerOrDeviceTransports() {
        noClasses().that().resideInAnyPackage(EVENT_INTAKE, EVENT_TRIGGER)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..worker..",
                        "..jobs.transport..",
                        "..jobs.executor..",
                        "..jobs.diagnostic..",
                        "..jobs.discovery..",
                        "..jobs.policy..",
                        "..jobs.failover..",
                        "..jobs.pathvalidation..",
                        "..jobs.staticroute..",
                        "..jobs.supportbundle..",
                        "..jobs.systemlog..",
                        "..jobs.pan..",
                        "..discovery.cp..",
                        "..discovery.pan..",
                        "..policy.asa..",
                        "..policy.fortigate..",
                        "..configuration..",
                        "..platform.advisoryfetch..",
                        "com.jcraft..",
                        "org.snmp4j..")
                .check(classes);
    }

    /**
     * Q8 (§1.1): {@code ui2-event-intake} holds zero {@code ui2-credential-store-key},
     * {@code ui2-artefact-store-key}, or {@code /app/artefact-store} PVC, and zero grants on
     * {@code jobs}, {@code policy_collection_request}, {@code devices}, {@code credentials}, or
     * {@code audit_log}.
     */
    @Test
    void eventIntakeHoldsNoCredentialArtefactOrCoreJobTableAccess() {
        noClasses().that().resideInAPackage(EVENT_INTAKE)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..persistence.artefact..",
                        "..persistence.credential..",
                        "..persistence.identity..",
                        "..persistence.job..",
                        "..persistence.jobrecords..",
                        "..persistence.device..",
                        "..persistence.audit..",
                        "..persistence.policy..")
                .check(classes);
        noClasses().that().resideInAPackage(EVENT_INTAKE)
                .should().dependOnClassesThat().haveSimpleNameEndingWith("StoreCipher")
                .check(classes);
    }

    /**
     * Q8 (§1.1): {@code ui2-event-intake} never spawns local subprocesses.
     */
    @Test
    void eventIntakeSpawnsNoSubprocess() {
        noClasses().that().resideInAPackage(EVENT_INTAKE)
                .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.ProcessBuilder")
                .check(classes);
        noClasses().that().resideInAPackage(EVENT_INTAKE)
                .should().callMethodWhere(new DescribedPredicate<>("call Runtime.exec") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        return call.getTargetOwner().isEquivalentTo(Runtime.class)
                                && "exec".equals(call.getName());
                    }
                })
                .check(classes);
    }

    /**
     * Q8 (§1.1): network listener isolation remains strictly in {@code ..eventintake..}, and
     * {@code ..eventtrigger..} holds no store cipher or artefact/credential store dependency.
     */
    @Test
    void eventTriggerHoldsNoStoreCipherOrRawServerSocket() {
        noClasses().that().resideInAPackage(EVENT_TRIGGER)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..persistence.artefact..",
                        "..persistence.credential..")
                .check(classes);
        noClasses().that().resideInAPackage(EVENT_TRIGGER)
                .should().dependOnClassesThat().haveSimpleNameEndingWith("StoreCipher")
                .check(classes);
        noClasses().that().resideInAPackage(EVENT_TRIGGER)
                .should().dependOnClassesThat().haveFullyQualifiedName("java.net.ServerSocket")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("javax.net.ssl.SSLServerSocket")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.nio.channels.ServerSocketChannel")
                .check(classes);
    }
}
